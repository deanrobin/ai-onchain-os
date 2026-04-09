package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.PerpOiWatchSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PerpOiWatchSnapshotRepository extends JpaRepository<PerpOiWatchSnapshot, Long> {

    /** 最新一条快照（用于页面展示当前状态） */
    Optional<PerpOiWatchSnapshot> findTopByExchangeAndSymbolOrderBySnappedAtDesc(
            String exchange, String symbol);

    /** 时间范围内的快照列表，按时间降序（用于历史弹窗） */
    List<PerpOiWatchSnapshot> findByExchangeAndSymbolAndSnappedAtAfterOrderBySnappedAtDesc(
            String exchange, String symbol, LocalDateTime after);

    /** 清理 7 天前数据，每次最多删 1000 条防止长事务 */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM perp_oi_watch_snapshot WHERE snapped_at < :before LIMIT 1000",
           nativeQuery = true)
    int deleteOldSnapshots(@Param("before") LocalDateTime before);
}
