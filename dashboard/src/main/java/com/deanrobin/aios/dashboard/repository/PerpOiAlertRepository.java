package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.PerpOiAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PerpOiAlertRepository extends JpaRepository<PerpOiAlert, Long> {

    /**
     * 检查指定品种是否存在仍在有效期内的告警（watch_until > now）。
     * 用于 PerpOiJob 判断是否需要新建告警（48h 冷却）。
     */
    boolean existsByExchangeAndSymbolAndWatchUntilAfter(
            String exchange, String symbol, LocalDateTime now);

    /**
     * 查询所有当前处于特别关注期的告警（watch_until > now），
     * 对每个 (exchange, symbol) 只取最新一条。
     */
    @Query("SELECT a FROM PerpOiAlert a WHERE a.watchUntil > :now " +
           "AND a.id = (SELECT MAX(b.id) FROM PerpOiAlert b " +
           "            WHERE b.exchange = a.exchange AND b.symbol = a.symbol) " +
           "ORDER BY a.alertedAt DESC")
    List<PerpOiAlert> findActiveWatches(@Param("now") LocalDateTime now);

    /**
     * 查询所有 watch_until > now 的 (exchange, symbol) 不重复列表，
     * 供 PerpWatchJob 做快照。
     */
    @Query("SELECT DISTINCT a.exchange, a.symbol FROM PerpOiAlert a WHERE a.watchUntil > :now")
    List<Object[]> findActiveWatchSymbols(@Param("now") LocalDateTime now);
}
