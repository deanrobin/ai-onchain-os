package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.PumpMarketCapSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface PumpMarketCapSnapshotRepository extends JpaRepository<PumpMarketCapSnapshot, Long> {

    /** 查某个 mint 的历史快照，按时间倒序最多 N 条。配合 idx_mint_checked_at 索引。 */
    @Query(value = "SELECT * FROM pump_market_cap_snapshot WHERE mint = :mint ORDER BY checked_at DESC LIMIT :limit", nativeQuery = true)
    List<PumpMarketCapSnapshot> findByMintRecent(String mint, int limit);

    /** 清理 N 天前的快照（SignalCleanupJob 每日调用，分批删除防长事务） */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM pump_market_cap_snapshot WHERE checked_at < :before LIMIT 500", nativeQuery = true)
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
