package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.BinanceSquarePost;
import com.deanrobin.aios.dashboard.model.BinanceSquareTokenStat;
import com.deanrobin.aios.dashboard.repository.BinanceSquarePostRepository;
import com.deanrobin.aios.dashboard.repository.BinanceSquareTokenStatRepository;
import com.deanrobin.aios.dashboard.service.BinanceSquareClient;
import com.deanrobin.aios.dashboard.service.BinanceTokenExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每 5 分钟抓一次币安广场帖子，抽取代币并写入 DB。
 *
 * 分页策略：每页 20 条，最多翻 10 页（200 条上限），页间 sleep 1 秒避免限流。
 * 早停策略：内存缓存最近一次 Job 见到的最新 post_id（首次启动从 DB 取最近一条），
 *          滚动过程中遇到该 id 即停止本轮抓取，等下次再跑。
 *
 * ⚠️ 不加 @Transactional（每次 save 是独立小事务）。
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class BinanceSquareFetchJob {

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");
    private static final int PAGE_SIZE      = 20;
    private static final int MAX_PAGES      = 10;     // 20 × 10 = 200 条上限
    private static final long SLEEP_MS      = 1_000L; // 页间 sleep 1s

    private final ObjectMapper mapper = new ObjectMapper();

    private final BinanceSquareClient                 client;
    private final BinanceSquarePostRepository         postRepo;
    private final BinanceSquareTokenStatRepository    statRepo;

    /** 上一次 Job 见到的最新 post_id（首次为 null，从 DB 加载）。 */
    private volatile String lastSeenPostId;

    @Scheduled(initialDelay = 30_000, fixedDelay = 300_000)
    public void run() {
        if (lastSeenPostId == null) {
            lastSeenPostId = postRepo.findFirstByOrderByIdDesc()
                    .map(BinanceSquarePost::getPostId)
                    .orElse(null);
            log.info("🔖 币安广场首次启动，lastSeenPostId 初始化={}", lastSeenPostId);
        }

        Set<String> whitelist = client.getWhitelist();
        int fetched = 0, saved = 0, tokensSaved = 0, skipped = 0;
        String newestThisRun = null;
        boolean stopped = false;

        outer:
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<Map<String, Object>> posts = client.fetchPosts(page, PAGE_SIZE);
            if (posts.isEmpty()) {
                if (page == 1) log.info("📡 币安广场抓取: 本轮无数据");
                break;
            }

            for (Map<String, Object> post : posts) {
                fetched++;
                String postId = strVal(post.get("id"));
                if (postId == null) { skipped++; continue; }

                if (newestThisRun == null) newestThisRun = postId;

                if (postId.equals(lastSeenPostId)) {
                    log.info("🛑 撞到 lastSeenPostId={}，page={}，停止本轮抓取", postId, page);
                    stopped = true;
                    break outer;
                }
                if (postRepo.existsByPostId(postId)) { skipped++; continue; }

                BinanceTokenExtractor.Extracted ex = BinanceTokenExtractor.extract(post);
                if (ex.getAll().isEmpty()) { skipped++; continue; }

                int likes    = toInt(post.get("likeCount"));
                int comments = toInt(post.get("commentCount"));
                int score    = likes + comments;
                LocalDateTime postDate = toLocalDate(post.get("date"));

                BinanceSquarePost row = new BinanceSquarePost();
                row.setPostId(postId);
                row.setAuthorName(strVal(post.get("authorName")));
                row.setContent(truncate(strVal(post.get("content")), 10_000));
                row.setLikeCount(likes);
                row.setCommentCount(comments);
                row.setScore(score);
                row.setTokens(toJsonArray(ex.getAll()));
                row.setPostDate(postDate);
                row.setFetchedAt(LocalDateTime.now());

                try {
                    postRepo.save(row);
                    saved++;
                } catch (DataIntegrityViolationException e) {
                    skipped++;
                    continue;
                }

                for (String token : ex.getAll()) {
                    BinanceSquareTokenStat st = new BinanceSquareTokenStat();
                    st.setPostId(postId);
                    st.setToken(token);
                    st.setInContent(ex.getFromContent().contains(token));
                    st.setInFields(ex.getFromFields().contains(token));
                    st.setInBinance(whitelist.contains(token));
                    st.setLikes(likes);
                    st.setComments(comments);
                    st.setScore(score);
                    st.setPostDate(postDate);
                    st.setCreatedAt(LocalDateTime.now());
                    try {
                        statRepo.save(st);
                        tokensSaved++;
                    } catch (DataIntegrityViolationException ignored) {
                        // uk_post_token 冲突，忽略
                    }
                }
            }

            // 整页处理完后 sleep 1s，避免限流
            if (page < MAX_PAGES && posts.size() >= PAGE_SIZE) {
                try { Thread.sleep(SLEEP_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            } else {
                // 最后一页（不足 PAGE_SIZE）说明没更多了
                break;
            }
        }

        if (newestThisRun != null) {
            lastSeenPostId = newestThisRun;
        }

        log.info("📡 币安广场抓取完成 抓={} 新帖={} 代币行={} 跳过={} 早停={} lastSeen={}",
                fetched, saved, tokensSaved, skipped, stopped, lastSeenPostId);
    }

    // ─── 辅助方法 ──────────────────────────────────────────────

    private String toJsonArray(Set<String> tokens) {
        try {
            return mapper.writeValueAsString(new ArrayList<>(tokens));
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String strVal(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; }
    }

    /** date 字段可能是秒级/毫秒级时间戳或 ISO 字符串。 */
    private static LocalDateTime toLocalDate(Object v) {
        if (v == null) return LocalDateTime.now();
        try {
            if (v instanceof Number n) {
                long val = n.longValue();
                if (val > 10_000_000_000L) {
                    return LocalDateTime.ofInstant(Instant.ofEpochMilli(val), CST);
                }
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(val), CST);
            }
            String s = v.toString().trim();
            if (s.matches("\\d+")) {
                long val = Long.parseLong(s);
                if (val > 10_000_000_000L) {
                    return LocalDateTime.ofInstant(Instant.ofEpochMilli(val), CST);
                }
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(val), CST);
            }
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}
