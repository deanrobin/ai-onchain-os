package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.PerpFundingRate;
import com.deanrobin.aios.dashboard.model.PerpInstrument;
import com.deanrobin.aios.dashboard.repository.PerpFundingRateRepository;
import com.deanrobin.aios.dashboard.repository.PerpInstrumentRepository;
import com.deanrobin.aios.dashboard.service.PerpApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hyperliquid 永续合约独立 Job（品种同步 + 资金费率）。
 * 与 OKX / Binance Job 完全独立，互不影响。
 *
 * Hyperliquid 的 metaAndAssetCtxs 接口一次返回所有品种信息 + 当前费率，
 * 因此品种同步与费率更新合并为一次 API 调用，复用结果。
 *
 * - syncAndFetchRates()  每 5 min：同步品种 + 更新全量资金费率
 * - fetchWatchedRates()  每 1 min：仅更新 is_watched=1 品种
 *
 * ⚠️ 不加 @Transactional
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class HyperliquidPerpJob {

    private static final String EXCHANGE = "HYPERLIQUID";

    private final PerpApiClient             perpApiClient;
    private final PerpInstrumentRepository  instrumentRepo;
    private final PerpFundingRateRepository fundingRateRepo;
    private final WebClient.Builder         webClientBuilder;

    @Value("${perp.alert-url:}")
    private String alertUrl;

    // ═══ 品种同步 + 全量费率（每 5 min，initialDelay 30s，与其他 Job 错开）
    // Hyperliquid 一次 API 调用返回品种和费率，合并处理效率更高
    @Scheduled(initialDelay = 30_000, fixedDelay = 300_000)
    public void syncAndFetchRates() {
        List<PerpApiClient.HyperliquidAsset> assets = perpApiClient.fetchHyperliquidAll();
        if (assets.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        int newCount = 0;
        List<String> newSymbols = new ArrayList<>();
        int rateSaved = 0;

        for (PerpApiClient.HyperliquidAsset asset : assets) {
            String symbol = asset.name();
            if (symbol == null || symbol.isBlank()) continue;

            // ── 品种同步 ──
            var opt = instrumentRepo.findByExchangeAndSymbol(EXCHANGE, symbol);
            PerpInstrument inst;
            if (opt.isEmpty()) {
                inst = new PerpInstrument();
                inst.setExchange(EXCHANGE);
                inst.setSymbol(symbol);
                inst.setBaseCurrency(symbol);
                inst.setQuoteCurrency("USD");
                inst.setFirstSeenAt(now);
                inst.setLastSeenAt(now);
                newCount++;
                newSymbols.add(symbol);
            } else {
                inst = opt.get();
                inst.setIsActive(true);
                inst.setLastSeenAt(now);
            }

            // ── 资金费率 ──
            BigDecimal rate = parseBD(asset.fundingRate());
            PerpFundingRate snap = new PerpFundingRate();
            snap.setExchange(EXCHANGE); snap.setSymbol(symbol);
            snap.setFundingRate(rate); snap.setFetchedAt(now);
            fundingRateRepo.save(snap);

            inst.setLatestFundingRate(rate);
            inst.setLatestFundingUpdatedAt(now);
            instrumentRepo.save(inst);
            rateSaved++;
        }

        if (newCount > 0) {
            log.info("🆕 Hyperliquid 新增永续合约 {} 个: {}", newCount, newSymbols);
            triggerAlert(newSymbols.size(), String.join(",", newSymbols.subList(0, Math.min(3, newSymbols.size()))));
        }
        if (rateSaved > 0) log.info("📊 Hyperliquid 资金费率更新 {} 条", rateSaved);
    }

    // ═══ 关注品种资金费率（每 1 min，initialDelay 50s）═══════════════
    @Scheduled(initialDelay = 50_000, fixedDelay = 60_000)
    public void fetchWatchedRates() {
        List<PerpInstrument> watched = instrumentRepo.findByIsWatchedTrue().stream()
                .filter(p -> EXCHANGE.equals(p.getExchange())).toList();
        if (watched.isEmpty()) return;

        // 一次调用获取所有，从中过滤关注品种
        List<PerpApiClient.HyperliquidAsset> assets = perpApiClient.fetchHyperliquidAll();
        if (assets.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        int saved = 0;
        for (PerpInstrument inst : watched) {
            for (PerpApiClient.HyperliquidAsset asset : assets) {
                if (inst.getSymbol().equals(asset.name())) {
                    BigDecimal rate = parseBD(asset.fundingRate());
                    PerpFundingRate snap = new PerpFundingRate();
                    snap.setExchange(EXCHANGE); snap.setSymbol(inst.getSymbol());
                    snap.setFundingRate(rate); snap.setFetchedAt(now);
                    fundingRateRepo.save(snap);
                    inst.setLatestFundingRate(rate); inst.setLatestFundingUpdatedAt(now);
                    instrumentRepo.save(inst);
                    saved++;
                    break;
                }
            }
        }
        if (saved > 0) log.debug("⭐ Hyperliquid 关注品种费率更新 {} 条", saved);
    }

    // ─── 飞书报警 ────────────────────────────────────────────────────
    private void triggerAlert(int count, String sample) {
        if (alertUrl == null || alertUrl.isBlank()) return;
        try {
            String text = String.format("🆕 Hyperliquid 新增永续合约\n数量：%d 个\n示例：%s", count, sample);
            Map<String, Object> body = Map.of("msg_type", "text", "content", Map.of("text", text));
            webClientBuilder.build().post().uri(alertUrl)
                    .header("Content-Type", "application/json").bodyValue(body)
                    .retrieve().bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(5))
                    .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                    .subscribe();
        } catch (Exception e) {
            log.warn("⚠️ Hyperliquid 飞书报警失败: {}", e.getMessage());
        }
    }

    private BigDecimal parseBD(Object obj) {
        if (obj == null) return null;
        try { return new BigDecimal(String.valueOf(obj)); } catch (Exception e) { return null; }
    }
}
