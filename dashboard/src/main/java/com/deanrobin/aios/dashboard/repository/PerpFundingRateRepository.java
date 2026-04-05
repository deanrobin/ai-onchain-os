package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.PerpFundingRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface PerpFundingRateRepository extends JpaRepository<PerpFundingRate, Long> {

    /** 查指定交易所、时间窗口内的所有费率记录（用于突变检测） */
    List<PerpFundingRate> findByExchangeAndFetchedAtBetween(
            String exchange, LocalDateTime from, LocalDateTime to);

    /** 清理 5 天前的快照（PerpCleanupJob 调用，每次 LIMIT 500 防长事务） */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM perp_funding_rate WHERE fetched_at < :before LIMIT 500", nativeQuery = true)
    int deleteOldSnapshots(@Param("before") LocalDateTime before);
}
