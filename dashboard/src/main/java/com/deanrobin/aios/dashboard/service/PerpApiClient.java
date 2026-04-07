package com.deanrobin.aios.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Perps 专用 API 客户端 —— 仅调用公开端点，无需鉴权。
 *
 * OKX:          https://www.okx.com/api/v5/public/*
 * Binance USDT-M: https://fapi.binance.com/fapi/v1/*
 * Hyperliquid:  https://api.hyperliquid.xyz/info  (POST)
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class PerpApiClient {

    private static final String OKX_BASE        = "https://www.okx.com";
    private static final String BINANCE_BASE     = "https://fapi.binance.com";
    private static final String HYPERLIQUID_BASE = "https://api.hyperliquid.xyz";
    private static final Duration TIMEOUT        = Duration.ofSeconds(15);
    /** 响应体最大缓冲 10MB，防止大 JSON 触发 DataBufferLimitException */
    private static final int MAX_BUFFER_BYTES    = 10 * 1024 * 1024;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    /** 构建带大缓冲区的 WebClient */
    private WebClient client(String baseUrl) {
        return webClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BUFFER_BYTES))
                        .build())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // OKX
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取 OKX 所有永续合约品种列表。
     * GET /api/v5/public/instruments?instType=SWAP
     * 返回 List<Map>，每项含 instId / baseCcy / quoteCcy
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchOkxInstruments() {
        try {
            Map<?, ?> resp = client(OKX_BASE)
                    .get()
                    .uri("/api/v5/public/instruments?instType=SWAP")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            if (resp == null || !"0".equals(String.valueOf(resp.get("code")))) {
                log.warn("⚠️ OKX instruments 返回异常: {}", resp);
                return List.of();
            }
            Object data = resp.get("data");
            if (data instanceof List<?> list) return (List<Map<String, Object>>) list;
        } catch (Exception e) {
            log.error("❌ OKX fetchInstruments 失败: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * 获取单个 OKX 永续合约的资金费率（一次只能查一个，需限速）。
     * GET /api/v5/public/funding-rate?instId=BTC-USDT-SWAP
     * 返回 Map 含 fundingRate / fundingTime（毫秒时间戳字符串）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchOkxFundingRate(String instId) {
        try {
            Map<?, ?> resp = client(OKX_BASE)
                    .get()
                    .uri("/api/v5/public/funding-rate?instId=" + instId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            if (resp == null || !"0".equals(String.valueOf(resp.get("code")))) return Map.of();
            Object data = resp.get("data");
            if (data instanceof List<?> list && !list.isEmpty()) {
                return (Map<String, Object>) list.get(0);
            }
        } catch (Exception e) {
            log.warn("⚠️ OKX fundingRate {} 失败: {}", instId, e.getMessage());
        }
        return Map.of();
    }

    // ═══════════════════════════════════════════════════════════════
    // Binance USDT-M Futures
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取 Binance USDT-M 所有永续合约品种。
     * GET /fapi/v1/exchangeInfo  → symbols[] where contractType=PERPETUAL
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchBinanceInstruments() {
        try {
            Map<?, ?> resp = client(BINANCE_BASE)
                    .get()
                    .uri("/fapi/v1/exchangeInfo")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            if (resp == null) return List.of();
            Object symbols = resp.get("symbols");
            if (!(symbols instanceof List<?> list)) return List.of();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    if ("PERPETUAL".equals(m.get("contractType"))
                            && "TRADING".equals(m.get("status"))) {
                        result.add((Map<String, Object>) m);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("❌ Binance fetchInstruments 失败: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * 批量获取 Binance 所有永续合约资金费率（一次返回全部，无需逐个请求）。
     * GET /fapi/v1/premiumIndex  → List<Map> 含 symbol / lastFundingRate / nextFundingTime
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchBinanceFundingRates() {
        try {
            List<?> resp = client(BINANCE_BASE)
                    .get()
                    .uri("/fapi/v1/premiumIndex")
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(TIMEOUT)
                    .block();
            if (resp == null) return List.of();
            return (List<Map<String, Object>>) resp;
        } catch (Exception e) {
            log.error("❌ Binance fetchFundingRates 失败: {}", e.getMessage());
        }
        return List.of();
    }

    // ═══════════════════════════════════════════════════════════════
    // Hyperliquid
    // ═══════════════════════════════════════════════════════════════

    /**
     * 一次性获取 Hyperliquid 所有品种 + 资金费率。
     * POST /info  body: {"type":"metaAndAssetCtxs"}
     * 返回二元数组: [meta, assetCtxs]
     * meta.universe[i].name  ↔  assetCtxs[i].funding
     */
    @SuppressWarnings("unchecked")
    public List<HyperliquidAsset> fetchHyperliquidAll() {
        try {
            List<?> resp = client(HYPERLIQUID_BASE)
                    .post()
                    .uri("/info")
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("type", "metaAndAssetCtxs"))
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(TIMEOUT)
                    .block();
            if (resp == null || resp.size() < 2) return List.of();

            Map<?, ?> meta = (Map<?, ?>) resp.get(0);
            List<?> ctxList = (List<?>) resp.get(1);

            List<?> universe = (List<?>) meta.get("universe");
            if (universe == null) return List.of();

            List<HyperliquidAsset> result = new ArrayList<>();
            for (int i = 0; i < universe.size() && i < ctxList.size(); i++) {
                Map<?, ?> asset = (Map<?, ?>) universe.get(i);
                Map<?, ?> ctx   = (Map<?, ?>) ctxList.get(i);
                String name    = String.valueOf(asset.get("name"));
                String funding = ctx.get("funding") != null ? String.valueOf(ctx.get("funding")) : null;
                result.add(new HyperliquidAsset(name, funding));
            }
            return result;
        } catch (Exception e) {
            log.error("❌ Hyperliquid fetchAll 失败: {}", e.getMessage());
        }
        return List.of();
    }

    // ─── 工具：毫秒时间戳 → LocalDateTime ───────────────────────────
    public static LocalDateTime msToLdt(Object msObj) {
        if (msObj == null) return null;
        try {
            long ms = Long.parseLong(String.valueOf(msObj));
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.of("Asia/Shanghai"));
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Hyperliquid 品种数据 VO ─────────────────────────────────
    public record HyperliquidAsset(String name, String fundingRate) {}
}
