package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.FourMemeToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface FourMemeTokenRepository extends JpaRepository<FourMemeToken, Long> {

    @Query(value = "SELECT * FROM four_meme_token ORDER BY received_at DESC LIMIT :limit", nativeQuery = true)
    List<FourMemeToken> findRecent(int limit);

    boolean existsByTokenAddress(String tokenAddress);

    @Query("SELECT t FROM FourMemeToken t WHERE t.receivedAt < :before AND t.checked10mAt IS NULL")
    List<FourMemeToken> findDueFor10m(LocalDateTime before);

    @Query("SELECT t FROM FourMemeToken t WHERE t.receivedAt < :before AND t.checked1hAt IS NULL")
    List<FourMemeToken> findDueFor1h(LocalDateTime before);

    @Query("SELECT t FROM FourMemeToken t WHERE t.receivedAt < :before AND t.lastCheckedAt IS NULL")
    List<FourMemeToken> findDueFor24h(LocalDateTime before);

    @Query("SELECT t FROM FourMemeToken t WHERE t.status = 'survived' ORDER BY t.currentMarketCap DESC NULLS LAST")
    List<FourMemeToken> findSurvivors();

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM four_meme_token WHERE id NOT IN (SELECT id FROM (SELECT id FROM four_meme_token ORDER BY received_at DESC LIMIT :keep) t)", nativeQuery = true)
    void retainLatest(int keep);
}
