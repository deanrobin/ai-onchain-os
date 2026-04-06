package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.KlineBar;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface KlineBarRepository extends JpaRepository<KlineBar, Long> {

    Optional<KlineBar> findBySymbolAndBarAndOpenTime(String symbol, String bar, LocalDateTime openTime);

    /** 按开盘时间倒序，取最近 N 条（用于 MA/EMA 计算） */
    @Query("SELECT k FROM KlineBar k WHERE k.symbol = :symbol AND k.bar = :bar ORDER BY k.openTime DESC")
    List<KlineBar> findLatestBars(@Param("symbol") String symbol,
                                  @Param("bar") String bar,
                                  Pageable pageable);

    /** 统计某 symbol+bar 的数据条数（判断是否需要批量补录历史） */
    long countBySymbolAndBar(String symbol, String bar);

    /** 按 bar 类型清理超期数据（00:20 定时任务调用） */
    @Modifying
    @Transactional
    @Query("DELETE FROM KlineBar k WHERE k.bar = :bar AND k.openTime < :cutoff")
    int deleteByBarAndOpenTimeBefore(@Param("bar") String bar,
                                     @Param("cutoff") LocalDateTime cutoff);
}
