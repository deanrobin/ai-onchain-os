package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.BinanceSquareTokenStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface BinanceSquareTokenStatRepository extends JpaRepository<BinanceSquareTokenStat, Long> {

    /**
     * 按时间窗口聚合代币热度。返回字段顺序：
     * token, score_sum, likes_sum, comments_sum, post_count, in_binance
     */
    @Query(value = "SELECT token, " +
            "       COALESCE(SUM(score),0)    AS score_sum, " +
            "       COALESCE(SUM(likes),0)    AS likes_sum, " +
            "       COALESCE(SUM(comments),0) AS comments_sum, " +
            "       COUNT(*)                  AS post_count, " +
            "       MAX(in_binance)           AS in_binance " +
            "FROM binance_square_token_stat " +
            "WHERE post_date >= :since " +
            "GROUP BY token " +
            "ORDER BY score_sum DESC " +
            "LIMIT :limit",
            nativeQuery = true)
    List<Object[]> aggregateSince(@Param("since") LocalDateTime since,
                                  @Param("limit") int limit);

    /**
     * 按「我们首次抓到帖子时间」聚合（= 发现时间，对推荐 feed 更直观）。
     */
    @Query(value = "SELECT token, " +
            "       COALESCE(SUM(score),0)    AS score_sum, " +
            "       COALESCE(SUM(likes),0)    AS likes_sum, " +
            "       COALESCE(SUM(comments),0) AS comments_sum, " +
            "       COUNT(*)                  AS post_count, " +
            "       MAX(in_binance)           AS in_binance " +
            "FROM binance_square_token_stat " +
            "WHERE created_at >= :since " +
            "GROUP BY token " +
            "ORDER BY score_sum DESC " +
            "LIMIT :limit",
            nativeQuery = true)
    List<Object[]> aggregateSinceByCreatedAt(@Param("since") LocalDateTime since,
                                             @Param("limit") int limit);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM binance_square_token_stat WHERE post_date < :cutoff LIMIT 500",
            nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
