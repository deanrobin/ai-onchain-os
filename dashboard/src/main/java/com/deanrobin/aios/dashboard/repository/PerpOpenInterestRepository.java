package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.PerpOpenInterest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PerpOpenInterestRepository extends JpaRepository<PerpOpenInterest, Long> {

    /**
     * 查询指定品种在 [fromTime, toTime] 时间段内最早一条 OI 快照（用于计算变化量）。
     */
    @Query("SELECT o FROM PerpOpenInterest o WHERE o.exchange = :exchange AND o.symbol = :symbol " +
           "AND o.fetchedAt BETWEEN :from AND :to ORDER BY o.fetchedAt ASC LIMIT 1")
    Optional<PerpOpenInterest> findEarliestInRange(
            @Param("exchange") String exchange,
            @Param("symbol")   String symbol,
            @Param("from")     LocalDateTime from,
            @Param("to")       LocalDateTime to);

    /**
     * 清理指定时间之前的 OI 快照。
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM PerpOpenInterest o WHERE o.fetchedAt < :cutoff")
    int deleteByFetchedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
