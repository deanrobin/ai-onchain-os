package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.BinanceTicker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BinanceTickerRepository extends JpaRepository<BinanceTicker, Long> {

    Optional<BinanceTicker> findBySymbol(String symbol);

    /** Top 20 按成交额降序 */
    @Query("SELECT t FROM BinanceTicker t ORDER BY t.quoteVolume DESC LIMIT 20")
    List<BinanceTicker> findTop20ByVolume();

    /** Top 20 按24h涨幅降序（涨幅榜） */
    @Query("SELECT t FROM BinanceTicker t ORDER BY t.priceChangePct DESC LIMIT 20")
    List<BinanceTicker> findTop20ByGainers();

    /** Top 20 按24h涨幅升序（跌幅榜） */
    @Query("SELECT t FROM BinanceTicker t ORDER BY t.priceChangePct ASC LIMIT 20")
    List<BinanceTicker> findTop20ByLosers();

    /** 全量记录数 */
    long count();
}
