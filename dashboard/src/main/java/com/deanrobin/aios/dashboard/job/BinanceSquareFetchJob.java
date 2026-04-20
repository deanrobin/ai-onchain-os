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
 * ⚠️ 不加 @Transactional（每次 save 是独立小事务）。
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class BinanceSquareFetchJob {

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");
    private final ObjectMapper mapper = new ObjectMapper();

    private final BinanceSquareClient                 client;
    private final BinanceSquarePostRepository         postRepo;
    private final BinanceSquareTokenStatRepository    statRepo;

    @Scheduled(initialDelay = 30_000, fixedDelay = 300_000)
    public void run() {
        List<Map<String, Object>> posts = client.fetchPosts();
        if (posts.isEmpty()) {
            log.info("📡 币安广场抓取: 本轮无数据");
            return;
        }

        Set<String> whitelist = client.getWhitelist();
        int fetched = 0, saved = 0, tokensSaved = 0, skipped = 0;

        for (Map<String, Object> post : posts) {
            fetched++;
            String postId = strVal(post.get("id"));
            if (postId == null) { skipped++; continue; }
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

        log.info("📡 币安广场抓取完成 抓={} 新帖={} 代币行={} 跳过={}",
                fetched, saved, tokensSaved, skipped);
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
