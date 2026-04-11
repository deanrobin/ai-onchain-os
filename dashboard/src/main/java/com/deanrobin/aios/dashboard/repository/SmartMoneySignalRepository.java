package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.SmartMoneySignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface SmartMoneySignalRepository extends JpaRepository<SmartMoneySignal, Long> {

    /**
     * 查最近信号（首页 / signals 页）。
     * 加 signal_time > 7天 时间过滤，配合 idx_signal_time 索引走范围扫描，
     * 避免全表扫描（表无限增长时 OR + 表达式条件会导致慢查询）。
     */
    @Query(value = "SELECT * FROM smart_money_signal" +
                   " WHERE signal_time > DATE_SUB(NOW(), INTERVAL 7 DAY)" +
                   " AND (market_cap_usd IS NULL OR market_cap_usd=0" +
                   "      OR (market_cap_usd>=10000 AND amount_usd/market_cap_usd<=0.5))" +
                   " ORDER BY signal_time DESC LIMIT :limit", nativeQuery = true)
    List<SmartMoneySignal> findRecent(@Param("limit") int limit);

    @Query(value = "SELECT * FROM smart_money_signal" +
                   " WHERE chain_index=:chain" +
                   " AND signal_time > DATE_SUB(NOW(), INTERVAL 7 DAY)" +
                   " AND (market_cap_usd IS NULL OR market_cap_usd=0" +
                   "      OR (market_cap_usd>=10000 AND amount_usd/market_cap_usd<=0.5))" +
                   " ORDER BY signal_time DESC LIMIT :limit", nativeQuery = true)
    List<SmartMoneySignal> findRecentByChain(@Param("chain") String chain, @Param("limit") int limit);

    /**
     * 查最近一条同链同 token 记录（用于判断是否需要更新）。
     * 配合 idx_chain_token_wt_time(chain_index, token_address, wallet_type, signal_time) 索引，
     * 直接走索引尾部取最大 signal_time，无需排序。每 10s 被调用 40+ 次，索引至关重要。
     */
    @Query(value = "SELECT * FROM smart_money_signal" +
                   " WHERE chain_index=:chain AND token_address=:addr AND wallet_type=:wt" +
                   " ORDER BY signal_time DESC LIMIT 1", nativeQuery = true)
    java.util.Optional<SmartMoneySignal> findLatest(@Param("chain") String chain,
                                                    @Param("addr") String addr,
                                                    @Param("wt") String wt);

    /** 清理 N 天前的历史信号（SignalCleanupJob 每日调用，分批删除防长事务） */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM smart_money_signal WHERE signal_time < :before LIMIT 500", nativeQuery = true)
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
