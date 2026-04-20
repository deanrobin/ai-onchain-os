package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.BinanceSquarePost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface BinanceSquarePostRepository extends JpaRepository<BinanceSquarePost, Long> {

    Optional<BinanceSquarePost> findByPostId(String postId);

    boolean existsByPostId(String postId);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM binance_square_post WHERE post_date < :cutoff LIMIT 500",
            nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
