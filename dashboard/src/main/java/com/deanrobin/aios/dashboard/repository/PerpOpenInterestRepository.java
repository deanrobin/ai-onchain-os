package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.PerpOpenInterest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PerpOpenInterestRepository extends JpaRepository<PerpOpenInterest, Long> {

    /**
     * 查询指定交易所每个品种最新的 OI 快照（每个 symbol 取 fetched_at 最大的那条）。
     */
    @Query(value = "SELECT o.* FROM perp_open_interest o " +
                   "WHERE o.exchange = :exchange " +
                   "AND o.fetched_at = (" +
                   "  SELECT MAX(o2.fetched_at) FROM perp_open_interest o2 " +
                   "  WHERE o2.exchange = o.exchange AND o2.symbol = o.symbol" +
                   ") ORDER BY o.oi_usdt DESC",
           nativeQuery = true)
    List<PerpOpenInterest> findLatestPerSymbol(@Param("exchange") String exchange);

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
