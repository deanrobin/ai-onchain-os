package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.PerpInstrument;
import com.deanrobin.aios.dashboard.repository.PerpInstrumentRepository;
import com.deanrobin.aios.dashboard.service.PerpApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 每 5 分钟同步三所永续合约品种列表。
 * 发现新品种 → 写 DB + HTTP 报警（PERP_ALERT_URL）。
 * ⚠️ 不加 @Transactional（规范约束：@Scheduled 方法禁止事务）
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class PerpInstrumentSyncJob {

    private final PerpApiClient            perpApiClient;
    private final PerpInstrumentRepository instrumentRepo;
    private final WebClient.Builder        webClientBuilder;

    @Value("${perp.alert-url:}")
    private String alertUrl;

    @Scheduled(initialDelay = 10_000, fixedDelay = 300_000)
    public void syncInstruments() {
        int totalNew = 0;
        totalNew += syncOkx();
        totalNew += syncBinance();
        totalNew += syncHyperliquid();
        if (totalNew > 0) {
            log.info("📡 Perps 品种同步完成，新增 {} 个", totalNew);
        }
    }

    // ─── OKX ───────────────────────────────────────────────────────
    private int syncOkx() {
        List<Map<String, Object>> instruments = perpApiClient.fetchOkxInstruments();
        if (instruments.isEmpty()) return 0;

        int newCount = 0;
        List<String> newSymbols = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map<String, Object> item : instruments) {
            String symbol = String.valueOf(item.getOrDefault("instId", ""));
            if (symbol.isBlank()) continue;
            String base  = String.valueOf(item.getOrDefault("baseCcy",  ""));
            String quote = String.valueOf(item.getOrDefault("quoteCcy", ""));

            var opt = instrumentRepo.findByExchangeAndSymbol("OKX", symbol);
            if (opt.isEmpty()) {
                PerpInstrument pi = new PerpInstrument();
                pi.setExchange("OKX");
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
                pi.setIsActive(true);
                pi.setLastSeenAt(now);
                instrumentRepo.save(pi);
            }
        }
        if (newCount > 0) {
            log.info("🆕 OKX 新增永续合约 {} 个: {}", newCount, newSymbols);
            triggerAlert("OKX", newSymbols.size(), String.join(",", newSymbols.subList(0, Math.min(3, newSymbols.size()))));
        }
        return newCount;
    }

    // ─── Binance ────────────────────────────────────────────────────
    private int syncBinance() {
        List<Map<String, Object>> instruments = perpApiClient.fetchBinanceInstruments();
        if (instruments.isEmpty()) return 0;

        int newCount = 0;
        List<String> newSymbols = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map<String, Object> item : instruments) {
            String symbol = String.valueOf(item.getOrDefault("symbol",     ""));
            String base   = String.valueOf(item.getOrDefault("baseAsset",  ""));
            String quote  = String.valueOf(item.getOrDefault("quoteAsset", ""));
            if (symbol.isBlank()) continue;

            var opt = instrumentRepo.findByExchangeAndSymbol("BINANCE", symbol);
            if (opt.isEmpty()) {
                PerpInstrument pi = new PerpInstrument();
                pi.setExchange("BINANCE");
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
                pi.setIsActive(true);
                pi.setLastSeenAt(now);
                instrumentRepo.save(pi);
            }
        }
        if (newCount > 0) {
            log.info("🆕 Binance 新增永续合约 {} 个: {}", newCount, newSymbols);
            triggerAlert("BINANCE", newSymbols.size(), String.join(",", newSymbols.subList(0, Math.min(3, newSymbols.size()))));
        }
        return newCount;
    }

    // ─── Hyperliquid ────────────────────────────────────────────────
    private int syncHyperliquid() {
        List<PerpApiClient.HyperliquidAsset> assets = perpApiClient.fetchHyperliquidAll();
        if (assets.isEmpty()) return 0;

        int newCount = 0;
        List<String> newSymbols = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (PerpApiClient.HyperliquidAsset asset : assets) {
            String symbol = asset.name();
            if (symbol == null || symbol.isBlank()) continue;

            var opt = instrumentRepo.findByExchangeAndSymbol("HYPERLIQUID", symbol);
            if (opt.isEmpty()) {
                PerpInstrument pi = new PerpInstrument();
                pi.setExchange("HYPERLIQUID");
                pi.setSymbol(symbol);
                pi.setBaseCurrency(symbol);
                pi.setQuoteCurrency("USD");
                pi.setFirstSeenAt(now);
                pi.setLastSeenAt(now);
                instrumentRepo.save(pi);
                newCount++;
                newSymbols.add(symbol);
            } else {
                PerpInstrument pi = opt.get();
                pi.setIsActive(true);
                pi.setLastSeenAt(now);
                instrumentRepo.save(pi);
            }
        }
        if (newCount > 0) {
            log.info("🆕 Hyperliquid 新增永续合约 {} 个: {}", newCount, newSymbols);
            triggerAlert("HYPERLIQUID", newSymbols.size(), String.join(",", newSymbols.subList(0, Math.min(3, newSymbols.size()))));
        }
        return newCount;
    }

    // ─── 报警 ────────────────────────────────────────────────────────
    private void triggerAlert(String exchange, int count, String sampleSymbols) {
        if (alertUrl == null || alertUrl.isBlank()) return;
        try {
            String url = alertUrl + "?exchange=" + exchange + "&count=" + count + "&symbols=" + sampleSymbols;
            webClientBuilder.build()
                    .get().uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(5))
                    .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                    .subscribe();
            log.info("🔔 Perps 报警已发送 exchange={} count={}", exchange, count);
        } catch (Exception e) {
            log.warn("⚠️ Perps 报警发送失败: {}", e.getMessage());
        }
    }
}
