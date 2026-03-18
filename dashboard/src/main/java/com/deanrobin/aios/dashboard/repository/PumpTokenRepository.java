package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.PumpToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface PumpTokenRepository extends JpaRepository<PumpToken, Long> {

    @Query(value = "SELECT * FROM pump_token ORDER BY received_at DESC LIMIT :limit", nativeQuery = true)
    List<PumpToken> findRecent(int limit);

    boolean existsByMint(String mint);

    // 保留最近 N 条，删除旧数据
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM pump_token WHERE id NOT IN (SELECT id FROM (SELECT id FROM pump_token ORDER BY received_at DESC LIMIT :keep) t)", nativeQuery = true)
    void retainLatest(int keep);
}
