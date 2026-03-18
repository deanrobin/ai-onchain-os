package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.PumpMarketCapSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PumpMarketCapSnapshotRepository extends JpaRepository<PumpMarketCapSnapshot, Long> {

    /** 查某个 mint 的历史快照，按时间倒序最多 N 条 */
    @Query(value = "SELECT * FROM pump_market_cap_snapshot WHERE mint = :mint ORDER BY checked_at DESC LIMIT :limit", nativeQuery = true)
    List<PumpMarketCapSnapshot> findByMintRecent(String mint, int limit);
}
