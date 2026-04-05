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
 * Binance USDT-M 永续合约独立 Job（品种同步 + 资金费率）。
 * 与 OKX / Hyperliquid Job 完全独立，互不影响。
 *
 * - syncInstruments()   每 5 min：同步 Binance USDT-M 品种，新品种飞书报警
 * - fetchAllRates()     每 10 min：批量拉全部资金费率（一次接口返回所有）
 * - fetchWatchedRates() 每 1 min：仅更新 is_watched=1 品种（从批量结果中过滤）
 *
 * ⚠️ 不加 @Transactional
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class BinancePerpJob {

    private static final String EXCHANGE = "BINANCE";

    private final PerpApiClient             perpApiClient;
    private final PerpInstrumentRepository  instrumentRepo;
    private final PerpFundingRateRepository fundingRateRepo;
    private final WebClient.Builder         webClientBuilder;

    @Value("${perp.alert-url:}")
    private String alertUrl;

    // ═══ 品种同步（每 5 min，initialDelay 20s，与 OKX 错开）════════
    @Scheduled(initialDelay = 20_000, fixedDelay = 300_000)
    public void syncInstruments() {
        List<Map<String, Object>> instruments = perpApiClient.fetchBinanceInstruments();
        if (instruments.isEmpty()) return;

        int newCount = 0;
        List<String> newSymbols = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map<String, Object> item : instruments) {
            String symbol = String.valueOf(item.getOrDefault("symbol",     ""));
            String base   = String.valueOf(item.getOrDefault("baseAsset",  ""));
            String quote  = String.valueOf(item.getOrDefault("quoteAsset", ""));
            if (symbol.isBlank()) continue;
            if (!"USDT".equalsIgnoreCase(quote)) continue;   // fapi 已是 USDT-M，保险过滤

            var opt = instrumentRepo.findByExchangeAndSymbol(EXCHANGE, symbol);
            if (opt.isEmpty()) {
                PerpInstrument pi = new PerpInstrument();
                pi.setExchange(EXCHANGE);
                pi.setSymbol(symbol);
                pi.setBaseCurrency(base);
                pi.setQuoteCurrency(quote);
                pi.setFirstSeenAt(now);
                pi.setLastSeenAt(now);
                instrumentRepo.save(pi);
                newCount++;
                newSymbols.add(symbol);
            } else {
                PerpInstrument pi = opt.get();
                // 只有状态变化时才写 DB
                if (!Boolean.TRUE.equals(pi.getIsActive())) {
                    pi.setIsActive(true);
                    pi.setLastSeenAt(now);
                    instrumentRepo.save(pi);
                }
            }
        }
        if (newCount > 0) {
            log.info("🆕 Binance 新增永续合约 {} 个: {}", newCount, newSymbols);
            triggerAlert(newSymbols.size(), String.join(",", newSymbols.subList(0, Math.min(3, newSymbols.size()))));
        }
    }

    // ═══ 全量资金费率（每 10 min，initialDelay 90s）══════════════════
    @Scheduled(initialDelay = 90_000, fixedDelay = 600_000)
    public void fetchAllRates() {
        LocalDateTime now = LocalDateTime.now();
        int saved = saveRates(perpApiClient.fetchBinanceFundingRates(), now);
        if (saved > 0) log.info("📊 Binance 资金费率全量更新 {} 条", saved);
    }

    // ═══ 关注品种资金费率（每 1 min，initialDelay 40s）═══════════════
    @Scheduled(initialDelay = 40_000, fixedDelay = 60_000)
    public void fetchWatchedRates() {
        List<PerpInstrument> watched = instrumentRepo.findByIsWatchedTrue().stream()
                .filter(p -> EXCHANGE.equals(p.getExchange())).toList();
        if (watched.isEmpty()) return;

        // Binance 批量接口一次返回所有，从中过滤关注品种
        List<Map<String, Object>> allRates = perpApiClient.fetchBinanceFundingRates();
        if (allRates.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        int saved = 0;
        for (PerpInstrument inst : watched) {
            for (Map<String, Object> item : allRates) {
                if (inst.getSymbol().equals(String.valueOf(item.get("symbol")))) {
                    BigDecimal rate    = parseBD(item.get("lastFundingRate"));
                    LocalDateTime next = PerpApiClient.msToLdt(item.get("nextFundingTime"));
                    PerpFundingRate snap = new PerpFundingRate();
                    snap.setExchange(EXCHANGE); snap.setSymbol(inst.getSymbol());
                    snap.setFundingRate(rate); snap.setNextFundingTime(next); snap.setFetchedAt(now);
                    fundingRateRepo.save(snap);
                    inst.setLatestFundingRate(rate); inst.setLatestFundingUpdatedAt(now);
                    instrumentRepo.save(inst);
                    saved++;
                    break;
                }
            }
        }
        if (saved > 0) log.debug("⭐ Binance 关注品种费率更新 {} 条", saved);
    }

    // ─── 内部：批量保存费率 ───────────────────────────────────────────
    private int saveRates(List<Map<String, Object>> rates, LocalDateTime now) {
        int saved = 0;
        for (Map<String, Object> item : rates) {
            String symbol = String.valueOf(item.getOrDefault("symbol", ""));
            if (symbol.isBlank()) continue;
            BigDecimal rate    = parseBD(item.get("lastFundingRate"));
            LocalDateTime next = PerpApiClient.msToLdt(item.get("nextFundingTime"));

            PerpFundingRate snap = new PerpFundingRate();
            snap.setExchange(EXCHANGE); snap.setSymbol(symbol);
            snap.setFundingRate(rate); snap.setNextFundingTime(next); snap.setFetchedAt(now);
            fundingRateRepo.save(snap);

            instrumentRepo.findByExchangeAndSymbol(EXCHANGE, symbol).ifPresent(inst -> {
                inst.setLatestFundingRate(rate);
                inst.setLatestFundingUpdatedAt(now);
                instrumentRepo.save(inst);
            });
            saved++;
        }
        return saved;
    }

    // ─── 飞书报警 ────────────────────────────────────────────────────
    private void triggerAlert(int count, String sample) {
        if (alertUrl == null || alertUrl.isBlank()) return;
        try {
            String text = String.format("🆕 Binance 新增永续合约\n数量：%d 个\n示例：%s", count, sample);
            Map<String, Object> body = Map.of("msg_type", "text", "content", Map.of("text", text));
            webClientBuilder.build().post().uri(alertUrl)
                    .header("Content-Type", "application/json").bodyValue(body)
                    .retrieve().bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(5))
                    .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                    .subscribe();
        } catch (Exception e) {
            log.warn("⚠️ Binance 飞书报警失败: {}", e.getMessage());
        }
    }

    private BigDecimal parseBD(Object obj) {
        if (obj == null) return null;
        try { return new BigDecimal(String.valueOf(obj)); } catch (Exception e) { return null; }
    }
}
