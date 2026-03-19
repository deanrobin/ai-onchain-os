package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.PumpToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface PumpTokenRepository extends JpaRepository<PumpToken, Long> {

    @Query(value = "SELECT * FROM pump_token ORDER BY received_at DESC LIMIT :limit", nativeQuery = true)
    List<PumpToken> findRecent(int limit);

    boolean existsByMint(String mint);

    // ── 各阶段待检查（每批 20 个，按时间正序）──────────────────────────────
    @Query(value = "SELECT * FROM pump_token WHERE received_at < :before AND checked_10m_at IS NULL ORDER BY received_at ASC LIMIT 20", nativeQuery = true)
    List<PumpToken> findDueFor10m(LocalDateTime before);
    @Query(value = "SELECT * FROM pump_token WHERE received_at < :before AND checked_20m_at IS NULL ORDER BY received_at ASC LIMIT 20", nativeQuery = true)
    List<PumpToken> findDueFor20m(LocalDateTime before);
    @Query(value = "SELECT * FROM pump_token WHERE received_at < :before AND checked_30m_at IS NULL ORDER BY received_at ASC LIMIT 20", nativeQuery = true)
    List<PumpToken> findDueFor30m(LocalDateTime before);
    @Query(value = "SELECT * FROM pump_token WHERE received_at < :before AND checked_45m_at IS NULL ORDER BY received_at ASC LIMIT 20", nativeQuery = true)
    List<PumpToken> findDueFor45m(LocalDateTime before);
    @Query(value = "SELECT * FROM pump_token WHERE received_at < :before AND checked_1h_at IS NULL ORDER BY received_at ASC LIMIT 20", nativeQuery = true)
    List<PumpToken> findDueFor1h(LocalDateTime before);
    @Query(value = "SELECT * FROM pump_token WHERE received_at < :before AND checked_4h_at IS NULL ORDER BY received_at ASC LIMIT 20", nativeQuery = true)
    List<PumpToken> findDueFor4h(LocalDateTime before);
    @Query(value = "SELECT * FROM pump_token WHERE received_at < :before AND checked_12h_at IS NULL ORDER BY received_at ASC LIMIT 20", nativeQuery = true)
    List<PumpToken> findDueFor12h(LocalDateTime before);
    @Query(value = "SELECT * FROM pump_token WHERE received_at < :before AND last_checked_at IS NULL ORDER BY received_at ASC LIMIT 20", nativeQuery = true)
    List<PumpToken> findDueFor24h(LocalDateTime before);

    // ── 积压跳过（超时未检查直接标记，不调 API）───────────────────────────
    @Modifying @Transactional
    @Query(value = "UPDATE pump_token SET checked_10m_at=NOW(),checked_20m_at=NOW(),checked_30m_at=NOW(),checked_45m_at=NOW(),checked_1h_at=NOW() WHERE received_at < :before AND checked_1h_at IS NULL", nativeQuery = true)
    int skipStale10m(LocalDateTime before);  // 超过 2h 的，直接跳过前5个阶段

    @Modifying @Transactional
    @Query(value = "UPDATE pump_token SET checked_4h_at=NOW() WHERE received_at < :before AND checked_4h_at IS NULL", nativeQuery = true)
    int skipStale1h(LocalDateTime before);   // 超过 6h 的跳过 4h 阶段

    /** 已存活 token，按最新市值倒序 */
    @Query("SELECT t FROM PumpToken t WHERE t.status = 'survived' ORDER BY t.currentMarketCap DESC NULLS LAST")
    List<PumpToken> findSurvivors();

    /** 取 SOL 当前价格（USD） */
    @Query(value = "SELECT price_usd FROM price_ticker WHERE symbol='SOL' ORDER BY updated_at DESC LIMIT 1", nativeQuery = true)
    java.math.BigDecimal findSolPrice();

    /** 取 BNB 当前价格（USD） */
    @Query(value = "SELECT price_usd FROM price_ticker WHERE symbol='BNB' ORDER BY updated_at DESC LIMIT 1", nativeQuery = true)
    java.math.BigDecimal findBnbPrice();

    // 保留最近 N 条，删除旧数据
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM pump_token WHERE id NOT IN (SELECT id FROM (SELECT id FROM pump_token ORDER BY received_at DESC LIMIT :keep) t)", nativeQuery = true)
    void retainLatest(int keep);
}
