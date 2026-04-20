package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.BinanceTicker;
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
 *
 * - OKX：每 15 分钟批量获取所有 USDT 永续合约（一次 API 调用）
 * - Binance：每 5 分钟，采集 is_watched 品种 ∪ 三榜 Top30（保证行情页 OI 完整）
 *   Binance 无批量 OI 接口，逐 symbol 请求；~60-90 个品种 × 200ms ≈ 12-18s/轮
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

    /** OKX OI：每 15 分钟批量采集（一次 API 搞定全部品种）*/
    @Scheduled(initialDelay = 120_000, fixedDelay = 900_000)
    public void fetchOkxOiAll() {
        fetchOkxOi(LocalDateTime.now());
    }

    /** Binance OI：每 5 分钟，覆盖 watched ∪ 三榜 Top30，保证行情页数据实时
     *  initialDelay 60s：BinanceTickerJob 30s 后启动，留 30s 缓冲确保 binance_ticker 有数据 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    public void fetchBinanceOiAll() {
        fetchBinanceOi(LocalDateTime.now());
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
            BigDecimal change24h = getChange24h(inst.getBaseCurrency());
            checkAndAlert("OKX", inst, oiUsd, change24h, now);
        }
        log.info("📊 OKX OI 采集完成 | 更新 {} 条", saved);
    }

    // ─── Binance：watched ∪ 三榜 Top30（每 5 分钟，确保行情页 OI 完整）────────
    private void fetchBinanceOi(LocalDateTime now) {
        // 一次加载所有活跃 Binance perp_instrument，供 symbol 查询
        Map<String, PerpInstrument> allInst = instrumentRepo.findByExchangeAndIsActiveTrue("BINANCE")
                .stream().collect(Collectors.toMap(PerpInstrument::getSymbol, p -> p, (a, b) -> a));

        // 预加载 binance_ticker 全量价格和 24H 涨跌幅
        List<BinanceTicker> allTickers = binanceTickerRepo.findAllWithPrice();
        Map<String, BigDecimal> tickerPrices = allTickers.stream()
                .collect(Collectors.toMap(BinanceTicker::getSymbol,
                        BinanceTicker::getLastPrice, (a, b) -> a));
        Map<String, BigDecimal> tickerChanges = allTickers.stream()
                .collect(Collectors.toMap(BinanceTicker::getSymbol,
                        BinanceTicker::getPriceChangePct, (a, b) -> a));

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

                // 优先用 binance_ticker 实时价格（全量），回退 price_ticker
                BigDecimal priceUsd = tickerPrices.get(symbol);
                if (priceUsd == null) {
                    String base = (inst != null && inst.getBaseCurrency() != null)
                            ? inst.getBaseCurrency() : symbol.replace("USDT", "");
                    priceUsd = getPrice(base);
                }
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
                    BigDecimal change24h = tickerChanges.get(symbol);
                    checkAndAlert("BINANCE", inst, oiUsd, change24h, now);
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
    private void checkAndAlert(String exchange, PerpInstrument inst, BigDecimal oiUsd,
                                BigDecimal change24h, LocalDateTime now) {
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

        // 发飞书告警（含持仓量 + 24H 涨跌幅）
        perpAlertService.sendOiBreakAlert(exchange, inst.getSymbol(),
                inst.getBaseCurrency(), oiUsd, change24h);
        log.info("🚨 OI突破5000万 | {}:{} | OI_USD={}", exchange, inst.getSymbol(), oiUsd);
    }

    // ─── 读取当前价格（从 price_ticker 表）───────────────────────────────
    private BigDecimal getPrice(String baseCurrency) {
        if (baseCurrency == null) return null;
        return priceRepo.findBySymbol(baseCurrency.toUpperCase())
                .map(p -> p.getPriceUsd())
                .orElse(null);
    }

    // ─── 读取 24H 涨跌幅（从 price_ticker 表，OKX 主要品种适用）────────
    private BigDecimal getChange24h(String baseCurrency) {
        if (baseCurrency == null) return null;
        return priceRepo.findBySymbol(baseCurrency.toUpperCase())
                .map(p -> p.getChange24h())
                .orElse(null);
    }

    private BigDecimal parseBD(Object obj) {
        if (obj == null) return null;
        try { return new BigDecimal(String.valueOf(obj)); } catch (Exception e) { return null; }
    }
}
