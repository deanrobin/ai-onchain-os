package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.PerpVolumeSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PerpVolumeSnapshotRepository extends JpaRepository<PerpVolumeSnapshot, Long> {

    /** 查询某品种最近一次快照（用于冷却判断） */
    Optional<PerpVolumeSnapshot> findTopBySymbolOrderBySnappedAtDesc(String symbol);

    /** 查找待发 24H 跟进：快照时间已过 24H 且未发送 */
    @Query("SELECT s FROM PerpVolumeSnapshot s " +
           "WHERE s.followup24hDone = false AND s.snappedAt <= :cutoff24h " +
           "ORDER BY s.snappedAt ASC")
    List<PerpVolumeSnapshot> findPending24hFollowup(@Param("cutoff24h") LocalDateTime cutoff24h);

    /** 查找待发 48H 跟进：快照时间已过 48H 且未发送 */
    @Query("SELECT s FROM PerpVolumeSnapshot s " +
           "WHERE s.followup48hDone = false AND s.snappedAt <= :cutoff48h " +
           "ORDER BY s.snappedAt ASC")
    List<PerpVolumeSnapshot> findPending48hFollowup(@Param("cutoff48h") LocalDateTime cutoff48h);
}
