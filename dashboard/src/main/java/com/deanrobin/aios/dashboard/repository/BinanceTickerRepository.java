package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.BinanceTicker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface BinanceTickerRepository extends JpaRepository<BinanceTicker, Long> {

    Optional<BinanceTicker> findBySymbol(String symbol);

    /** Top 20 按成交额降序 */
    @Query("SELECT t FROM BinanceTicker t ORDER BY t.quoteVolume DESC LIMIT 20")
    List<BinanceTicker> findTop20ByVolume();

    /** Top 50 按成交额降序（供 OI 采集覆盖行情页所有可见品种）*/
    @Query("SELECT t FROM BinanceTicker t ORDER BY t.quoteVolume DESC LIMIT 50")
    List<BinanceTicker> findTop50ByVolume();

    /** Top 20 按24h涨幅降序（涨幅榜） */
    @Query("SELECT t FROM BinanceTicker t ORDER BY t.priceChangePct DESC LIMIT 20")
    List<BinanceTicker> findTop20ByGainers();

    /** Top 20 按24h涨幅升序（跌幅榜） */
    @Query("SELECT t FROM BinanceTicker t ORDER BY t.priceChangePct ASC LIMIT 20")
    List<BinanceTicker> findTop20ByLosers();

    /** 删除不在活跃品种集合中的旧记录（清理已下线合约） */
    @Transactional
    @Modifying
    @Query("DELETE FROM BinanceTicker t WHERE t.symbol NOT IN :symbols")
    int deleteBySymbolNotIn(@Param("symbols") Set<String> symbols);

    /** 全量记录数 */
    long count();
}
