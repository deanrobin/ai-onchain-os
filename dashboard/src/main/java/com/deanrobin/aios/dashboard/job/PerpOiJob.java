package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.PerpInstrument;
import com.deanrobin.aios.dashboard.model.PerpOpenInterest;
import com.deanrobin.aios.dashboard.repository.PerpInstrumentRepository;
import com.deanrobin.aios.dashboard.repository.PerpOpenInterestRepository;
import com.deanrobin.aios.dashboard.repository.PriceTickerRepository;
import com.deanrobin.aios.dashboard.service.PerpApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 合约持仓量（Open Interest）定时采集 Job。
 * 每 15 分钟执行一次，采集后存入 perp_open_interest 表，
 * 同时更新 perp_instrument 的 latest_oi / latest_oi_usd 缓存字段。
 *
 * - OKX：批量获取所有 USDT 永续合约持仓量（一次 API 调用）
 * - Binance：仅采集 is_watched=true 品种（需逐个请求，数量少）
 *
 * ⚠️ 不加 @Transactional，避免长事务锁 DB
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class PerpOiJob {

    private static final long DELAY_MS = 500L;

    private final PerpApiClient              perpApiClient;
    private final PerpInstrumentRepository  instrumentRepo;
    private final PerpOpenInterestRepository oiRepo;
    private final PriceTickerRepository     priceRepo;

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
        }
        log.info("📊 OKX OI 采集完成 | 更新 {} 条", saved);
    }

    // ─── Binance：仅采集关注品种（逐个请求）─────────────────────────────
    private void fetchBinanceOi(LocalDateTime now) {
        List<PerpInstrument> watched = instrumentRepo.findByIsWatchedTrue().stream()
                .filter(p -> "BINANCE".equals(p.getExchange()))
                .collect(Collectors.toList());
        if (watched.isEmpty()) return;

        int saved = 0;
        for (PerpInstrument inst : watched) {
            try {
                Map<String, Object> resp = perpApiClient.fetchBinanceOpenInterest(inst.getSymbol());
                if (resp.isEmpty()) continue;
                BigDecimal oi = parseBD(resp.get("openInterest"));
                if (oi == null) continue;

                BigDecimal priceUsd = getPrice(inst.getBaseCurrency());
                BigDecimal oiUsd = (priceUsd != null) ? oi.multiply(priceUsd) : null;

                PerpOpenInterest snap = new PerpOpenInterest();
                snap.setExchange("BINANCE");
                snap.setSymbol(inst.getSymbol());
                snap.setOiCoin(oi);
                snap.setOiUsdt(oiUsd);
                snap.setFetchedAt(now);
                oiRepo.save(snap);

                inst.setLatestOi(oi);
                inst.setLatestOiUsd(oiUsd);
                inst.setLatestOiUpdatedAt(now);
                instrumentRepo.save(inst);
                saved++;
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("⚠️ Binance OI {} 失败: {}", inst.getSymbol(), e.getMessage());
            }
        }
        if (saved > 0) log.info("📊 Binance OI 采集完成 | 更新 {} 条", saved);
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
