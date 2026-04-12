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
    // Binance USDT-M Futures — 扩展接口
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取单个品种的 24h 行情（价格涨跌幅、当前价格、成交额等）。
     * GET /fapi/v1/ticker/24hr?symbol=BTCUSDT
     * 关键字段：priceChangePercent / lastPrice / quoteVolume
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchBinanceTicker24h(String symbol) {
        try {
            Map<?, ?> resp = client(BINANCE_BASE)
                    .get()
                    .uri("/fapi/v1/ticker/24hr?symbol=" + symbol)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            if (resp != null) return (Map<String, Object>) resp;
        } catch (Exception e) {
            log.warn("⚠️ Binance ticker24h {} 失败: {}", symbol, e.getMessage());
        }
        return Map.of();
    }

    /**
     * 获取指定品种的 K 线数据（倒序返回，最后一条为当前 bar，可能未收盘）。
     * GET /fapi/v1/klines?symbol=BTCUSDT&interval=1h&limit=22
     * 返回二维数组，每行索引：
     *   0=开盘时间(ms)  1=开  2=高  3=低  4=收  5=基础量  6=收盘时间(ms)
     *   7=计价量(USDT)  8=成交笔数  9=主动买入基础量  10=主动买入计价量
     */
    @SuppressWarnings("unchecked")
    public List<List<Object>> fetchBinanceKlines(String symbol, String interval, int limit) {
        try {
            List<?> resp = client(BINANCE_BASE)
                    .get()
                    .uri("/fapi/v1/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit)
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(TIMEOUT)
                    .block();
            if (resp != null) return (List<List<Object>>) resp;
        } catch (Exception e) {
            log.warn("⚠️ Binance klines {}/{} 失败: {}", symbol, interval, e.getMessage());
        }
        return List.of();
    }

    // ═══════════════════════════════════════════════════════════════
    // CoinGecko 公开接口 — 供应量数据（无需 API Key）
    // ═══════════════════════════════════════════════════════════════

    private static final String COINGECKO_BASE = "https://api.coingecko.com";

    /**
     * 获取市值排名前 N 页（每页 250 个）的代币供应量数据。
     * GET /api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=250&page={page}
     * 关键字段：symbol / id / circulating_supply / total_supply / max_supply
     *
     * 注：Binance 公开期货 API 不提供代币总量/流通量数据，因此使用 CoinGecko 补全。
     *     免费接口有限速（约 10-30 次/min），12H 刷新一次即可。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchCoinGeckoMarkets(int page) {
        try {
            String uri = "/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc" +
                         "&per_page=250&page=" + page + "&sparkline=false&locale=en";
            List<?> resp = client(COINGECKO_BASE)
                    .get()
                    .uri(uri)
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            if (resp != null) return (List<Map<String, Object>>) resp;
        } catch (Exception e) {
            log.warn("⚠️ CoinGecko markets page={} 失败: {}", page, e.getMessage());
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
                String oi      = ctx.get("openInterest") != null ? String.valueOf(ctx.get("openInterest")) : null;
                result.add(new HyperliquidAsset(name, funding, oi));
            }
            return result;
        } catch (Exception e) {
            log.error("❌ Hyperliquid fetchAll 失败: {}", e.getMessage());
        }
        return List.of();
    }

    // ═══════════════════════════════════════════════════════════════
    // OKX Open Interest
    // ═══════════════════════════════════════════════════════════════

    /**
     * 批量获取 OKX 所有永续合约当前持仓量（一次返回全部）。
     * GET /api/v5/public/open-interest?instType=SWAP
     * 返回列表，每项含 instId / oi（合约张数）/ oiCcy（基础货币数量）/ ts（毫秒时间戳）
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchOkxOpenInterestAll() {
        try {
            Map<?, ?> resp = client(OKX_BASE)
                    .get()
                    .uri("/api/v5/public/open-interest?instType=SWAP")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            if (resp == null || !"0".equals(String.valueOf(resp.get("code")))) {
                log.warn("⚠️ OKX open-interest 返回异常: {}", resp);
                return List.of();
            }
            Object data = resp.get("data");
            if (data instanceof List<?> list) return (List<Map<String, Object>>) list;
        } catch (Exception e) {
            log.error("❌ OKX fetchOpenInterestAll 失败: {}", e.getMessage());
        }
        return List.of();
    }

    // ═══════════════════════════════════════════════════════════════
    // Binance Open Interest
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取单个 Binance USDT-M 永续合约的当前持仓量。
     * GET /fapi/v1/openInterest?symbol=BTCUSDT
     * 返回 Map 含 openInterest（基础资产数量）/ symbol / time
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchBinanceOpenInterest(String symbol) {
        try {
            Map<?, ?> resp = client(BINANCE_BASE)
                    .get()
                    .uri("/fapi/v1/openInterest?symbol=" + symbol)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(TIMEOUT)
                    .block();
            if (resp == null) return Map.of();
            return (Map<String, Object>) resp;
        } catch (Exception e) {
            log.warn("⚠️ Binance openInterest {} 失败: {}", symbol, e.getMessage());
        }
        return Map.of();
    }

    /**
     * 获取 Binance USDT-M 持仓量（OI），返回 USDT 计价值。
     * GET /futures/data/openInterestHist?symbol=BTCUSDT&period=5m&limit=1
     * 返回最新一条 sumOpenInterestValue（USDT）。
     */
    @SuppressWarnings("unchecked")
    public Double fetchBinanceOIUsdt(String binanceSymbol) {
        try {
            String uri = String.format(
                    "/futures/data/openInterestHist?symbol=%s&period=5m&limit=1", binanceSymbol);
            List<?> resp = client(BINANCE_BASE)
                    .get().uri(uri)
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(TIMEOUT)
                    .block();
            if (resp == null || resp.isEmpty()) return null;
            Object item = resp.get(0);
            if (!(item instanceof Map<?, ?> m)) return null;
            Object val = m.get("sumOpenInterestValue");
            if (val == null) return null;
            return Double.parseDouble(String.valueOf(val));
        } catch (Exception e) {
            log.warn("⚠️ Binance OI {} 失败: {}", binanceSymbol, e.getMessage());
        }
        return null;
    }

    /**
     * 获取 Binance 主动买/卖量比。
     * GET /futures/data/takerlongshortRatio?symbol=BTCUSDT&period=5m&limit=1
     * 返回 Map 含 buySellRatio（买量/卖量）/ buyVol / sellVol（均为字符串小数）。
     * buySellRatio > 1 代表主动买入量大于主动卖出量（多头主导）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchBinanceTakerRatio(String binanceSymbol) {
        try {
            String uri = String.format(
                    "/futures/data/takerlongshortRatio?symbol=%s&period=5m&limit=1",
                    binanceSymbol);
            List<?> resp = client(BINANCE_BASE)
                    .get().uri(uri)
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(TIMEOUT)
                    .block();
            if (resp == null || resp.isEmpty()) return Map.of();
            Object item = resp.get(0);
            if (item instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (Exception e) {
            log.warn("⚠️ Binance takerRatio {} 失败: {}", binanceSymbol, e.getMessage());
        }
        return Map.of();
    }

    /**
     * 获取 Binance 全球账户多空比（散户视角）。
     * GET /futures/data/globalLongShortAccountRatio?symbol=BTCUSDT&period=5m&limit=1
     * 返回 Map 含 longShortRatio / longAccount / shortAccount（均为字符串小数）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchBinanceLSRatio(String binanceSymbol) {
        try {
            String uri = String.format(
                    "/futures/data/globalLongShortAccountRatio?symbol=%s&period=5m&limit=1",
                    binanceSymbol);
            List<?> resp = client(BINANCE_BASE)
                    .get().uri(uri)
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(TIMEOUT)
                    .block();
            if (resp == null || resp.isEmpty()) return Map.of();
            Object item = resp.get(0);
            if (item instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (Exception e) {
            log.warn("⚠️ Binance LS ratio {} 失败: {}", binanceSymbol, e.getMessage());
        }
        return Map.of();
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
    public record HyperliquidAsset(String name, String fundingRate, String openInterest) {}
}
