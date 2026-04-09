package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.PerpInstrument;
import com.deanrobin.aios.dashboard.model.PerpOiAlert;
import com.deanrobin.aios.dashboard.model.PerpOpenInterest;
import com.deanrobin.aios.dashboard.repository.BinanceTickerRepository;
import com.deanrobin.aios.dashboard.repository.PerpInstrumentRepository;
import com.deanrobin.aios.dashboard.repository.PerpOiAlertRepository;
import com.deanrobin.aios.dashboard.repository.PerpOpenInterestRepository;
import com.deanrobin.aios.dashboard.repository.PriceTickerRepository;
import com.deanrobin.aios.dashboard.service.PerpAlertService;
import com.deanrobin.aios.dashboard.service.PerpApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 合约持仓量（Open Interest）定时采集 Job。
 * 每 15 分钟执行一次，采集后存入 perp_open_interest 表，
 * 同时更新 perp_instrument 的 latest_oi / latest_oi_usd 缓存字段。
 *
 * 采集完成后检查 OI 是否 >= 5000万 USD（OI_THRESHOLD），
 * 满足条件且 48h 内未告警 → 写入 perp_oi_alert + 发飞书。
 *
 * - OKX：批量获取所有 USDT 永续合约持仓量（一次 API 调用）
 * - Binance：采集 is_watched=true 品种 ∪ 成交量 Top50 品种（确保行情页 OI 数据完整）
 *
 * ⚠️ 不加 @Transactional，避免长事务锁 DB
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class PerpOiJob {

    /** OI 突破阈值：5000万 USD */
    static final BigDecimal OI_THRESHOLD = new BigDecimal("50000000");

    private static final long DELAY_MS = 200L;   // Binance OI 品种间间隔（fapi 限速宽松）

    private final PerpApiClient              perpApiClient;
    private final PerpInstrumentRepository   instrumentRepo;
    private final PerpOpenInterestRepository oiRepo;
    private final PerpOiAlertRepository      alertRepo;
    private final PerpAlertService           perpAlertService;
    private final PriceTickerRepository      priceRepo;
    private final BinanceTickerRepository    binanceTickerRepo;

    /**
     * 每 15 分钟采集一次持仓量。
     * initialDelay 120s：等待资金费率 Job 完成初始化后再启动。
     */
    @Scheduled(initialDelay = 120_000, fixedDelay = 900_000)
    public void fetchAll() {
        LocalDateTime now = LocalDateTime.now();
        fetchOkxOi(now);
        fetchBinanceOi(now);
    }

    // ─── OKX：批量获取所有品种 ─────────────────────────────────────────
    private void fetchOkxOi(LocalDateTime now) {
        List<Map<String, Object>> data = perpApiClient.fetchOkxOpenInterestAll();
        if (data.isEmpty()) return;

        // 将 instId → oiCcy 构建 Map
        Map<String, String> oiMap = data.stream()
                .filter(m -> m.get("instId") != null && m.get("oiCcy") != null)
                .collect(Collectors.toMap(
                        m -> String.valueOf(m.get("instId")),
                        m -> String.valueOf(m.get("oiCcy")),
                        (a, b) -> a
                ));

        List<PerpInstrument> instruments = instrumentRepo.findByExchangeAndIsActiveTrue("OKX");
        int saved = 0;
        for (PerpInstrument inst : instruments) {
            String oiStr = oiMap.get(inst.getSymbol());
            if (oiStr == null) continue;
            BigDecimal oi = parseBD(oiStr);
            if (oi == null) continue;

            // 估算 USD：oiCcy × 当前价格（基础货币）
            BigDecimal priceUsd = getPrice(inst.getBaseCurrency());
            BigDecimal oiUsd = (priceUsd != null) ? oi.multiply(priceUsd) : null;

            // 写快照
            PerpOpenInterest snap = new PerpOpenInterest();
            snap.setExchange("OKX");
            snap.setSymbol(inst.getSymbol());
            snap.setOiCoin(oi);
            snap.setOiUsdt(oiUsd);
            snap.setFetchedAt(now);
            oiRepo.save(snap);

            // 更新 perp_instrument 缓存
            inst.setLatestOi(oi);
            inst.setLatestOiUsd(oiUsd);
            inst.setLatestOiUpdatedAt(now);
            instrumentRepo.save(inst);
            saved++;

            // ── 阈值检测：是否触发特别关注 ──
            checkAndAlert("OKX", inst, oiUsd, now);
        }
        log.info("📊 OKX OI 采集完成 | 更新 {} 条", saved);
    }

    // ─── Binance：watched 品种 ∪ 成交量 Top50（确保行情页 OI 完整）─────────
    private void fetchBinanceOi(LocalDateTime now) {
        // 一次加载所有活跃 Binance perp_instrument，供 symbol 查询
        Map<String, PerpInstrument> allInst = instrumentRepo.findByExchangeAndIsActiveTrue("BINANCE")
                .stream().collect(Collectors.toMap(PerpInstrument::getSymbol, p -> p, (a, b) -> a));

        // 目标品种：watched ∪ 三榜 Top30（精准覆盖行情页全部可见品种，不采冷门合约）
        Set<String> targets = new LinkedHashSet<>();
        instrumentRepo.findByIsWatchedTrue().stream()
                .filter(p -> "BINANCE".equals(p.getExchange()))
                .map(PerpInstrument::getSymbol)
                .forEach(targets::add);
        binanceTickerRepo.findTop30ByVolume().stream() .map(t -> t.getSymbol()).forEach(targets::add);
        binanceTickerRepo.findTop30ByGainers().stream().map(t -> t.getSymbol()).forEach(targets::add);
        binanceTickerRepo.findTop30ByLosers().stream() .map(t -> t.getSymbol()).forEach(targets::add);

        if (targets.isEmpty()) return;
        log.info("📡 Binance OI 开始采集 | 三榜 Top30 并集 {} 个品种", targets.size());

        int saved = 0;
        for (String symbol : targets) {
            PerpInstrument inst = allInst.get(symbol);
            try {
                log.info("📡 查询 BINANCE:{} 持仓情况...", symbol);
                Map<String, Object> resp = perpApiClient.fetchBinanceOpenInterest(symbol);
                if (resp.isEmpty()) { sleepMs(DELAY_MS); continue; }
                BigDecimal oi = parseBD(resp.get("openInterest"));
                if (oi == null) { sleepMs(DELAY_MS); continue; }

                String base = (inst != null && inst.getBaseCurrency() != null)
                        ? inst.getBaseCurrency() : symbol.replace("USDT", "");
                BigDecimal priceUsd = getPrice(base);
                BigDecimal oiUsd = (priceUsd != null) ? oi.multiply(priceUsd) : null;

                PerpOpenInterest snap = new PerpOpenInterest();
                snap.setExchange("BINANCE");
                snap.setSymbol(symbol);
                snap.setOiCoin(oi);
                snap.setOiUsdt(oiUsd);
                snap.setFetchedAt(now);
                oiRepo.save(snap);

                if (inst != null) {
                    inst.setLatestOi(oi);
                    inst.setLatestOiUsd(oiUsd);
                    inst.setLatestOiUpdatedAt(now);
                    instrumentRepo.save(inst);
                    checkAndAlert("BINANCE", inst, oiUsd, now);
                }
                saved++;
                sleepMs(DELAY_MS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
                log.warn("⚠️ Binance OI {} 失败: {}", symbol, e.getMessage());
            }
        }
        log.info("📊 Binance OI 采集完成 | 更新 {} / {} 条", saved, targets.size());
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── 阈值检测：OI >= 5000万 且 48h 内未告警 → 触发 ─────────────────
    private void checkAndAlert(String exchange, PerpInstrument inst, BigDecimal oiUsd, LocalDateTime now) {
        if (oiUsd == null || oiUsd.compareTo(OI_THRESHOLD) < 0) return;

        // 检查是否已存在仍在有效期内的告警（watch_until > now）
        boolean activeWatch = alertRepo.existsByExchangeAndSymbolAndWatchUntilAfter(
                exchange, inst.getSymbol(), now);
        if (activeWatch) return;  // 48h 冷却中，跳过

        // 写入告警记录，watch_until = now + 48h
        PerpOiAlert alert = new PerpOiAlert();
        alert.setExchange(exchange);
        alert.setSymbol(inst.getSymbol());
        alert.setOiUsd(oiUsd);
        alert.setAlertedAt(now);
        alert.setWatchUntil(now.plusHours(48));
        alertRepo.save(alert);

        // 发飞书告警
        perpAlertService.sendOiBreakAlert(exchange, inst.getSymbol(),
                inst.getBaseCurrency(), oiUsd);
        log.info("🚨 OI突破5000万 | {}:{} | OI_USD={}", exchange, inst.getSymbol(), oiUsd);
    }

    // ─── 读取当前价格（从 price_ticker 表）───────────────────────────────
    private BigDecimal getPrice(String baseCurrency) {
        if (baseCurrency == null) return null;
        return priceRepo.findBySymbol(baseCurrency.toUpperCase())
                .map(p -> p.getPriceUsd())
                .orElse(null);
    }

    private BigDecimal parseBD(Object obj) {
        if (obj == null) return null;
        try { return new BigDecimal(String.valueOf(obj)); } catch (Exception e) { return null; }
    }
}
