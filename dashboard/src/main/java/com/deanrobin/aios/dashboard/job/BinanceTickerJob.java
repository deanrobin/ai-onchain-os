package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.BinanceTicker;
import com.deanrobin.aios.dashboard.repository.BinanceTickerRepository;
import com.deanrobin.aios.dashboard.repository.PerpInstrumentRepository;
import com.deanrobin.aios.dashboard.service.PerpAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 每分钟拉取 Binance U本位永续合约 24h 行情，upsert 到 binance_ticker 表。
 * 成交额超过 5000w USDT 时触发飞书报警（每个品种每小时一次）。
 *
 * ⚠️ 不加 @Transactional
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class BinanceTickerJob {

    private static final String BINANCE_BASE   = "https://fapi.binance.com";
    private static final String TICKER_URI     = "/fapi/v1/ticker/24hr";
    private static final int    MAX_BUF        = 10 * 1024 * 1024;
    private static final Duration TIMEOUT      = Duration.ofSeconds(15);

    /** 成交额报警阈值：5000w USDT */
    private static final BigDecimal VOLUME_ALERT_THRESHOLD = new BigDecimal("50000000");

    private final BinanceTickerRepository  tickerRepo;
    private final PerpInstrumentRepository instrumentRepo;
    private final PerpAlertService         perpAlertService;
    private final WebClient.Builder        webClientBuilder;

    // initialDelay 错开其他 Binance job（BinancePerpJob initialDelay 20s/90s）
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void fetchTickers() {
        List<Map<String, Object>> raw = fetchFromBinance();
        if (raw.isEmpty()) return;

        // 取 perp_instrument 中 BINANCE 在交易的品种，过滤掉已下线合约
        Set<String> activeSymbols = instrumentRepo.findByExchangeAndIsActiveTrue("BINANCE")
                .stream()
                .map(p -> p.getSymbol())
                .collect(Collectors.toSet());

        LocalDateTime now   = LocalDateTime.now();

        // 批量预加载所有现有记录（1次 SELECT 替代 300次 findBySymbol）
        Map<String, BinanceTicker> existingMap = tickerRepo.findAll()
                .stream().collect(Collectors.toMap(BinanceTicker::getSymbol, t -> t));

        List<BinanceTicker> toSave = new ArrayList<>();

        for (Map<String, Object> item : raw) {
            String symbol = str(item, "symbol");
            if (symbol.isBlank()) continue;
            // 只保留 USDT 结算 且 perp_instrument 中存在的活跃合约
            if (!symbol.endsWith("USDT")) continue;
            if (!activeSymbols.contains(symbol)) continue;

            BigDecimal lastPrice      = decimal(item, "lastPrice");
            BigDecimal priceChangePct = decimal(item, "priceChangePercent");
            BigDecimal quoteVolume    = decimal(item, "quoteVolume");
            if (lastPrice == null || quoteVolume == null) continue;

            // 基础货币 = symbol 去掉 "USDT" 后缀
            String base = symbol.substring(0, symbol.length() - 4);

            // 复用现有对象（有 id → UPDATE）或新建（null id → INSERT）
            BinanceTicker ticker = existingMap.getOrDefault(symbol, new BinanceTicker());
            ticker.setSymbol(symbol);
            ticker.setBaseCurrency(base);
            ticker.setLastPrice(lastPrice);
            ticker.setPriceChangePct(priceChangePct != null ? priceChangePct : BigDecimal.ZERO);
            ticker.setQuoteVolume(quoteVolume);
            Object cnt = item.get("count");
            if (cnt != null) {
                try { ticker.setTradeCount(Integer.parseInt(String.valueOf(cnt))); } catch (Exception ignored) {}
            }
            ticker.setFetchedAt(now);
            toSave.add(ticker);

            // 成交额报警
            if (quoteVolume.compareTo(VOLUME_ALERT_THRESHOLD) >= 0) {
                perpAlertService.checkVolumeAlert(symbol, quoteVolume, lastPrice, priceChangePct);
            }
        }

        // 批量写入（1次事务替代 300次单行 save）
        int upserted = toSave.size();
        if (!toSave.isEmpty()) {
            tickerRepo.saveAll(toSave);
        }

        // 清理 DB 里已不在活跃品种集合中的旧记录（如 A2Z、ALPACA 等）
        if (!activeSymbols.isEmpty()) {
            int deleted = tickerRepo.deleteBySymbolNotIn(activeSymbols);
            if (deleted > 0) log.info("🗑️ Binance ticker 清理下线合约 {} 条", deleted);
        }

        log.info("📊 Binance ticker 更新={} 条", upserted);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchFromBinance() {
        try {
            WebClient client = webClientBuilder.clone()
                    .baseUrl(BINANCE_BASE)
                    .exchangeStrategies(ExchangeStrategies.builder()
                            .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BUF))
                            .build())
                    .build();
            List<?> resp = client.get()
                    .uri(TICKER_URI)
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(TIMEOUT)
                    .block();
            if (resp == null) return List.of();
            return (List<Map<String, Object>>) resp;
        } catch (Exception e) {
            log.warn("⚠️ Binance ticker 拉取失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static BigDecimal decimal(Map<String, Object> m, String key) {
        try {
            String s = str(m, key);
            return s.isEmpty() ? null : new BigDecimal(s);
        } catch (Exception e) {
            return null;
        }
    }
}
