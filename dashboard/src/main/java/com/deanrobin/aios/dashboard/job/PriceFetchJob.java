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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 每 10 秒从 OKX 公开市场 API 拉 BTC/ETH/BNB/SOL 价格及 24h 交易量，写入 price_ticker 表。
 * OKX 公开端点无需签名：GET https://www.okx.com/api/v5/market/ticker?instId=BTC-USDT
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class PriceFetchJob {

    private static final List<String[]> TARGETS = List.of(
        new String[]{"BTC",  "BTC-USDT"},
        new String[]{"ETH",  "ETH-USDT"},
        new String[]{"BNB",  "BNB-USDT"},
        new String[]{"SOL",  "SOL-USDT"},
        new String[]{"XAUT", "XAUT-USDT"}   // Tether Gold（黄金代币）
    );

    private final PriceTickerRepository priceRepo;
    private final WebClient.Builder webClientBuilder;

    @Scheduled(initialDelay = 5_000, fixedDelay = 10_000)
    public void fetchPrices() {
        WebClient client = webClientBuilder.baseUrl("https://www.okx.com").build();

        // 一次批量加载所有已有 ticker，减少 DB 查询：5×SELECT → 1×SELECT
        Map<String, PriceTicker> existing = priceRepo.findAll()
                .stream().collect(Collectors.toMap(PriceTicker::getSymbol, t -> t));

        List<PriceTicker> toSave = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (String[] pair : TARGETS) {
            String symbol = pair[0];
            String instId = pair[1];
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

                BigDecimal price   = parseBD(tick.get("last"));
                BigDecimal open24  = parseBD(tick.get("open24h"));
                BigDecimal vol24h  = parseBD(tick.get("volCcy24h")); // USDT 成交额

                BigDecimal change24h = null;
                if (price != null && open24 != null && open24.compareTo(BigDecimal.ZERO) != 0) {
                    change24h = price.subtract(open24)
                        .divide(open24, 6, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(4, java.math.RoundingMode.HALF_UP);
                }

                PriceTicker pt = existing.getOrDefault(symbol, new PriceTicker());
                if (pt.getSymbol() == null) pt.setSymbol(symbol);
                pt.setPriceUsd(price);
                pt.setChange24h(change24h);
                pt.setVolume24h(vol24h);
                pt.setUpdatedAt(now);
                toSave.add(pt);
            } catch (Exception e) {
                log.warn("⚠️ 价格拉取失败 {}: {}", symbol, e.getMessage());
            }
        }

        // 5×save → 1×saveAll，减少 DB round-trip
        if (!toSave.isEmpty()) {
            priceRepo.saveAll(toSave);
            log.debug("💹 价格已更新 {} 个代币", toSave.size());
        }
    }

    private BigDecimal parseBD(Object obj) {
        if (obj == null) return null;
        try { return new BigDecimal(String.valueOf(obj)); } catch (Exception e) { return null; }
    }
}
