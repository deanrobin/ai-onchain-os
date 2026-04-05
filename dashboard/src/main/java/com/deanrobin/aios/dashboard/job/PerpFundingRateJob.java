package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.PerpFundingRate;
import com.deanrobin.aios.dashboard.model.PerpInstrument;
import com.deanrobin.aios.dashboard.repository.PerpFundingRateRepository;
import com.deanrobin.aios.dashboard.repository.PerpInstrumentRepository;
import com.deanrobin.aios.dashboard.service.PerpApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 资金费率抓取任务。
 *
 * - fetchWatchedFundingRates()：每 1 分钟执行，只抓 is_watched=1 的品种
 * - fetchAllFundingRates()：每 10 分钟执行，抓全部品种
 *
 * OKX 每次只能查单个品种，请求间隔 1.2s（避免 429）。
 * Binance / Hyperliquid 批量接口，一次性返回全部，无需间隔。
 *
 * ⚠️ 不加 @Transactional（规范约束：@Scheduled 方法禁止事务）
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class PerpFundingRateJob {

    private static final long OKX_DELAY_MS = 1200L;

    private final PerpApiClient            perpApiClient;
    private final PerpInstrumentRepository instrumentRepo;
    private final PerpFundingRateRepository fundingRateRepo;

    /** 防止全量任务并发重入 */
    private final AtomicBoolean allRunning = new AtomicBoolean(false);

    // ─── 每 1 分钟：特别关注品种 ─────────────────────────────────────
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void fetchWatchedFundingRates() {
        List<PerpInstrument> watched = instrumentRepo.findByIsWatchedTrue();
        if (watched.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        int saved = 0;

        for (PerpInstrument inst : watched) {
            try {
                saved += fetchAndSave(inst, now);
                if ("OKX".equals(inst.getExchange())) {
                    Thread.sleep(OKX_DELAY_MS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("⚠️ 关注品种费率获取失败 {}/{}: {}", inst.getExchange(), inst.getSymbol(), e.getMessage());
            }
        }
        if (saved > 0) log.debug("⭐ 关注品种资金费率更新 {} 条", saved);
    }

    // ─── 每 10 分钟：全量品种 ────────────────────────────────────────
    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
    public void fetchAllFundingRates() {
        if (!allRunning.compareAndSet(false, true)) {
            log.warn("⚠️ PerpFundingRateJob 全量任务上次未完成，跳过本次");
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            int saved = 0;
            saved += fetchOkxAll(now);
            saved += fetchBinanceAll(now);
            saved += fetchHyperliquidAll(now);
            log.info("📊 Perps 资金费率全量更新 {} 条", saved);
        } finally {
            allRunning.set(false);
        }
    }

    // ─── OKX 全量（逐个，限速） ─────────────────────────────────────
    private int fetchOkxAll(LocalDateTime now) {
        List<PerpInstrument> list = instrumentRepo.findByExchangeAndIsActiveTrue("OKX");
        int saved = 0;
        for (PerpInstrument inst : list) {
            try {
                saved += fetchAndSave(inst, now);
                Thread.sleep(OKX_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("⚠️ OKX 费率获取失败 {}: {}", inst.getSymbol(), e.getMessage());
            }
        }
        return saved;
    }

    // ─── Binance 批量 ───────────────────────────────────────────────
    private int fetchBinanceAll(LocalDateTime now) {
        List<Map<String, Object>> rates = perpApiClient.fetchBinanceFundingRates();
        if (rates.isEmpty()) return 0;
        int saved = 0;
        for (Map<String, Object> item : rates) {
            String symbol = String.valueOf(item.getOrDefault("symbol", ""));
            if (symbol.isBlank()) continue;
            BigDecimal rate = parseBD(item.get("lastFundingRate"));
            LocalDateTime nextTime = PerpApiClient.msToLdt(item.get("nextFundingTime"));

            // 写快照
            PerpFundingRate snap = new PerpFundingRate();
            snap.setExchange("BINANCE");
            snap.setSymbol(symbol);
            snap.setFundingRate(rate);
            snap.setNextFundingTime(nextTime);
            snap.setFetchedAt(now);
            fundingRateRepo.save(snap);

            // 更新品种缓存
            instrumentRepo.findByExchangeAndSymbol("BINANCE", symbol).ifPresent(inst -> {
                inst.setLatestFundingRate(rate);
                inst.setLatestFundingUpdatedAt(now);
                instrumentRepo.save(inst);
            });
            saved++;
        }
        return saved;
    }

    // ─── Hyperliquid 批量 ───────────────────────────────────────────
    private int fetchHyperliquidAll(LocalDateTime now) {
        List<PerpApiClient.HyperliquidAsset> assets = perpApiClient.fetchHyperliquidAll();
        if (assets.isEmpty()) return 0;
        int saved = 0;
        for (PerpApiClient.HyperliquidAsset asset : assets) {
            String symbol = asset.name();
            if (symbol == null || symbol.isBlank()) continue;
            BigDecimal rate = parseBD(asset.fundingRate());

            PerpFundingRate snap = new PerpFundingRate();
            snap.setExchange("HYPERLIQUID");
            snap.setSymbol(symbol);
            snap.setFundingRate(rate);
            snap.setFetchedAt(now);
            fundingRateRepo.save(snap);

            instrumentRepo.findByExchangeAndSymbol("HYPERLIQUID", symbol).ifPresent(inst -> {
                inst.setLatestFundingRate(rate);
                inst.setLatestFundingUpdatedAt(now);
                instrumentRepo.save(inst);
            });
            saved++;
        }
        return saved;
    }

    // ─── 通用：单品种获取并保存（OKX 及关注品种） ─────────────────────
    private int fetchAndSave(PerpInstrument inst, LocalDateTime now) {
        switch (inst.getExchange()) {
            case "OKX" -> {
                Map<String, Object> data = perpApiClient.fetchOkxFundingRate(inst.getSymbol());
                if (data.isEmpty()) return 0;
                BigDecimal rate     = parseBD(data.get("fundingRate"));
                LocalDateTime next  = PerpApiClient.msToLdt(data.get("fundingTime"));

                PerpFundingRate snap = new PerpFundingRate();
                snap.setExchange("OKX");
                snap.setSymbol(inst.getSymbol());
                snap.setFundingRate(rate);
                snap.setNextFundingTime(next);
                snap.setFetchedAt(now);
                fundingRateRepo.save(snap);

                inst.setLatestFundingRate(rate);
                inst.setLatestFundingUpdatedAt(now);
                instrumentRepo.save(inst);
                return 1;
            }
            case "BINANCE" -> {
                // 单个 Binance 查询（关注品种专用）
                List<Map<String, Object>> all = perpApiClient.fetchBinanceFundingRates();
                for (Map<String, Object> item : all) {
                    if (inst.getSymbol().equals(String.valueOf(item.get("symbol")))) {
                        BigDecimal rate    = parseBD(item.get("lastFundingRate"));
                        LocalDateTime next = PerpApiClient.msToLdt(item.get("nextFundingTime"));
                        PerpFundingRate snap = new PerpFundingRate();
                        snap.setExchange("BINANCE"); snap.setSymbol(inst.getSymbol());
                        snap.setFundingRate(rate); snap.setNextFundingTime(next); snap.setFetchedAt(now);
                        fundingRateRepo.save(snap);
                        inst.setLatestFundingRate(rate); inst.setLatestFundingUpdatedAt(now);
                        instrumentRepo.save(inst);
                        return 1;
                    }
                }
            }
            case "HYPERLIQUID" -> {
                // 单个 Hyperliquid 查询（关注品种专用）
                List<PerpApiClient.HyperliquidAsset> assets = perpApiClient.fetchHyperliquidAll();
                for (PerpApiClient.HyperliquidAsset asset : assets) {
                    if (inst.getSymbol().equals(asset.name())) {
                        BigDecimal rate = parseBD(asset.fundingRate());
                        PerpFundingRate snap = new PerpFundingRate();
                        snap.setExchange("HYPERLIQUID"); snap.setSymbol(inst.getSymbol());
                        snap.setFundingRate(rate); snap.setFetchedAt(now);
                        fundingRateRepo.save(snap);
                        inst.setLatestFundingRate(rate); inst.setLatestFundingUpdatedAt(now);
                        instrumentRepo.save(inst);
                        return 1;
                    }
                }
            }
        }
        return 0;
    }

    private BigDecimal parseBD(Object obj) {
        if (obj == null) return null;
        try { return new BigDecimal(String.valueOf(obj)); } catch (Exception e) { return null; }
    }
}
