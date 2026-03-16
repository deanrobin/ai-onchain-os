package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.PriceTicker;
import com.deanrobin.aios.dashboard.repository.PriceTickerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 每 30 秒从 OKX 公开市场 API 拉 BTC/ETH/BNB/SOL 价格，写入 price_ticker 表。
 * OKX 公开端点无需签名：GET https://www.okx.com/api/v5/market/ticker?instId=BTC-USDT
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class PriceFetchJob {

    private static final List<String[]> TARGETS = List.of(
        new String[]{"BTC", "BTC-USDT"},
        new String[]{"ETH", "ETH-USDT"},
        new String[]{"BNB", "BNB-USDT"},
        new String[]{"SOL", "SOL-USDT"}
    );

    private final PriceTickerRepository priceRepo;
    private final WebClient.Builder webClientBuilder;

    @Scheduled(initialDelay = 5000, fixedDelay = 30000)
    public void fetchPrices() {
        WebClient client = webClientBuilder.baseUrl("https://www.okx.com").build();
        int updated = 0;
        for (String[] pair : TARGETS) {
            String symbol  = pair[0];
            String instId  = pair[1];
            try {
                Map<?, ?> resp = client.get()
                    .uri("/api/v5/market/ticker?instId=" + instId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(java.time.Duration.ofSeconds(5));
                if (resp == null || !"0".equals(String.valueOf(resp.get("code")))) continue;

                Object data = resp.get("data");
                if (!(data instanceof List<?> list) || list.isEmpty()) continue;
                Map<?, ?> tick = (Map<?, ?>) list.get(0);

                BigDecimal price  = parseBD(tick.get("last"));
                BigDecimal open24 = parseBD(tick.get("open24h"));
                BigDecimal change24h = null;
                if (price != null && open24 != null && open24.compareTo(BigDecimal.ZERO) != 0) {
                    change24h = price.subtract(open24)
                        .divide(open24, 6, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(4, java.math.RoundingMode.HALF_UP);
                }

                PriceTicker pt = priceRepo.findBySymbol(symbol).orElseGet(() -> {
                    PriceTicker n = new PriceTicker(); n.setSymbol(symbol); return n;
                });
                pt.setPriceUsd(price);
                pt.setChange24h(change24h);
                pt.setUpdatedAt(LocalDateTime.now());
                priceRepo.save(pt);
                updated++;
            } catch (Exception e) {
                log.warn("⚠️ 价格拉取失败 {}: {}", symbol, e.getMessage());
            }
        }
        if (updated > 0) log.debug("💹 价格已更新 {} 个代币", updated);
    }

    private BigDecimal parseBD(Object obj) {
        if (obj == null) return null;
        try { return new BigDecimal(String.valueOf(obj)); } catch (Exception e) { return null; }
    }
}
