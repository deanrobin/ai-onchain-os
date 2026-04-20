package com.deanrobin.aios.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 币安广场帖子抓取 + 币安现货代币白名单（仅用于标记，不过滤）。
 *
 * 对齐 python/binance_square_fetcher.py：
 *   POST https://www.binance.com/bapi/composite/v9/friendly/pgc/feed/feed-recommend/list
 *   body: {"pageIndex":N,"pageSize":20,"scene":"web-homepage","contentIds":[]}
 *   Headers: clienttype/lang/bnc-uuid/bnc-time-zone/versioncode/csrftoken/referer/origin
 *   成功判定: data.code == "000000"，列表在 data.data.vos
 */
@Log4j2
@Service
public class BinanceSquareClient {

    private static final String DEFAULT_FEED_URL =
            "https://www.binance.com/bapi/composite/v9/friendly/pgc/feed/feed-recommend/list";
    private static final String EXCHANGE_INFO =
            "https://api.binance.com/api/v3/exchangeInfo";

    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String bncUuid = UUID.randomUUID().toString();

    @Value("${binance-square.feed-url:" + DEFAULT_FEED_URL + "}")
    private String feedUrl;

    private volatile Set<String> whitelistCache = Collections.emptySet();
    private volatile long whitelistRefreshedAt = 0L;

    public BinanceSquareClient(WebClient.Builder builder) {
        // exchangeInfo 响应约 2-4 MB，默认 256 KB 装不下，调到 16 MB。
        org.springframework.web.reactive.function.client.ExchangeStrategies strategies =
                org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build();
        this.client = builder
                .exchangeStrategies(strategies)
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/120.0.0.0 Safari/537.36")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * 抓取广场帖子单页。pageIndex 从 1 开始；pageSize 通常 20。
     * 失败返回空列表。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchPosts(int pageIndex, int pageSize) {
        Map<String, Object> payload = Map.of(
                "pageIndex",  pageIndex,
                "pageSize",   pageSize,
                "scene",      "web-homepage",
                "contentIds", List.of());
        try {
            String body = client.post().uri(feedUrl)
                    .header("Content-Type",   "application/json")
                    .header("clienttype",     "web")
                    .header("lang",           "en")
                    .header("bnc-uuid",       bncUuid)
                    .header("bnc-time-zone",  "Asia/Shanghai")
                    .header("versioncode",    "web")
                    .header("csrftoken",      "d41d8cd98f00b204e9800998ecf8427e")
                    .header("referer",        "https://www.binance.com/en/square")
                    .header("origin",         "https://www.binance.com")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            if (body == null || body.isEmpty()) return Collections.emptyList();

            Map<String, Object> root = mapper.readValue(body, Map.class);
            Object code = root.get("code");
            if (code != null && !"000000".equals(String.valueOf(code))) {
                log.warn("⚠️ 币安广场 feed 业务错误 page={} code={} msg={}",
                        pageIndex, code, root.get("message"));
                return Collections.emptyList();
            }
            Object dataObj = root.get("data");
            if (!(dataObj instanceof Map<?, ?> data)) return Collections.emptyList();

            Object vos = firstNonNull(data.get("vos"), data.get("list"), data.get("contents"));
            if (vos instanceof List<?> arr) {
                return (List<Map<String, Object>>) (List<?>) arr;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("⚠️ 币安广场 feed 抓取失败 page={}: {}", pageIndex, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 现货白名单：仅用于标记，24h 刷新，失败沿用旧值。 */
    @SuppressWarnings("unchecked")
    public Set<String> getWhitelist() {
        long now = System.currentTimeMillis();
        if (whitelistRefreshedAt > 0 && now - whitelistRefreshedAt < 24 * 3600_000L) {
            return whitelistCache;
        }
        try {
            String body = client.get().uri(EXCHANGE_INFO)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .block();
            if (body == null) return whitelistCache;
            Map<String, Object> root = mapper.readValue(body, Map.class);
            Object syms = root.get("symbols");
            if (!(syms instanceof List<?> list)) return whitelistCache;

            Set<String> fresh = new HashSet<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> mp)) continue;
                if (!"TRADING".equals(String.valueOf(mp.get("status")))) continue;
                Object base = mp.get("baseAsset");
                if (base instanceof String s && !s.isEmpty()) fresh.add(s);
            }
            if (!fresh.isEmpty()) {
                whitelistCache = fresh;
                whitelistRefreshedAt = now;
                log.info("✅ 币安现货白名单刷新 {} 个", fresh.size());
            }
            return whitelistCache;
        } catch (Exception e) {
            log.warn("⚠️ 币安白名单拉取失败: {}", e.getMessage());
            return whitelistCache;
        }
    }

    private static Object firstNonNull(Object... items) {
        for (Object it : items) if (it != null) return it;
        return null;
    }
}
