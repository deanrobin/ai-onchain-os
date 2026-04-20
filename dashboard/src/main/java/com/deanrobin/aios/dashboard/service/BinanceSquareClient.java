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

/**
 * 币安广场帖子抓取 + 币安现货代币白名单（仅用于标记，不过滤）。
 *
 * - 广场帖子端点可通过 binance-square.feed-url 配置，默认使用公开 feed 接口；
 *   接口返回结构假设存在 data.vos（hot feed 常见结构）。失败时直接返回空列表，
 *   不阻塞 Job 继续运行。
 * - 现货白名单缓存 24 小时，刷新失败时沿用旧值。
 */
@Log4j2
@Service
public class BinanceSquareClient {

    private static final String DEFAULT_FEED_URL =
            "https://www.binance.com/bapi/composite/v1/public/content/community/square/feed?pageIndex=1&pageSize=50&scene=square";
    private static final String EXCHANGE_INFO =
            "https://api.binance.com/api/v3/exchangeInfo";

    private static final java.util.regex.Pattern PAGE_INDEX_RE =
            java.util.regex.Pattern.compile("([?&])pageIndex=\\d+");
    private static final java.util.regex.Pattern PAGE_SIZE_RE  =
            java.util.regex.Pattern.compile("([?&])pageSize=\\d+");

    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${binance-square.feed-url:" + DEFAULT_FEED_URL + "}")
    private String feedUrl;

    private volatile Set<String> whitelistCache = Collections.emptySet();
    private volatile long whitelistRefreshedAt = 0L;

    public BinanceSquareClient(WebClient.Builder builder) {
        this.client = builder
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (Linux) aios-dashboard/0.1")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * 抓取广场帖子单页。pageIndex 从 1 开始；pageSize 通常 20。
     * 失败返回空列表。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchPosts(int pageIndex, int pageSize) {
        String url = buildPagedUrl(feedUrl, pageIndex, pageSize);
        try {
            String body = client.get().uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            if (body == null || body.isEmpty()) return Collections.emptyList();

            Map<String, Object> root = mapper.readValue(body, Map.class);
            Object dataObj = root.get("data");
            if (!(dataObj instanceof Map<?, ?> data)) return Collections.emptyList();

            // 常见结构：data.vos / data.list / data.contents
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

    /** 替换或追加 pageIndex/pageSize 参数。 */
    static String buildPagedUrl(String baseUrl, int pageIndex, int pageSize) {
        String url = baseUrl;
        if (PAGE_INDEX_RE.matcher(url).find()) {
            url = PAGE_INDEX_RE.matcher(url).replaceFirst("$1pageIndex=" + pageIndex);
        } else {
            url += (url.contains("?") ? "&" : "?") + "pageIndex=" + pageIndex;
        }
        if (PAGE_SIZE_RE.matcher(url).find()) {
            url = PAGE_SIZE_RE.matcher(url).replaceFirst("$1pageSize=" + pageSize);
        } else {
            url += "&pageSize=" + pageSize;
        }
        return url;
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
