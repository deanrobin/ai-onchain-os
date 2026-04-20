package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.BinanceSquareRankSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BinanceSquareRankSnapshotRepository
        extends JpaRepository<BinanceSquareRankSnapshot, Long> {

    /** 取某个窗口的最近一次快照时间（快照时间点以毫秒级去重，取 MAX）。 */
    @Query("SELECT MAX(s.snapshotAt) FROM BinanceSquareRankSnapshot s " +
            "WHERE s.windowHours = :window")
    Optional<LocalDateTime> findLatestSnapshotTime(@Param("window") int window);

    /** 取某个窗口中最近一次「早于 :before」的快照时间（用于与当前榜做对比）。 */
    @Query("SELECT MAX(s.snapshotAt) FROM BinanceSquareRankSnapshot s " +
            "WHERE s.windowHours = :window AND s.snapshotAt < :before")
    Optional<LocalDateTime> findLatestSnapshotTimeBefore(@Param("window") int window,
                                                         @Param("before")  LocalDateTime before);

    /** 某时间点某窗口的完整 Top 榜。 */
    List<BinanceSquareRankSnapshot> findByWindowHoursAndSnapshotAtOrderByRankNo(
            Integer windowHours, LocalDateTime snapshotAt);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM binance_square_rank_snapshot WHERE snapshot_at < :cutoff LIMIT 500",
            nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
