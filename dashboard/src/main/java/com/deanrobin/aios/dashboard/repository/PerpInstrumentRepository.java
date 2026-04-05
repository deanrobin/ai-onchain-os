package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.PerpInstrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PerpInstrumentRepository extends JpaRepository<PerpInstrument, Long> {

    Optional<PerpInstrument> findByExchangeAndSymbol(String exchange, String symbol);

    List<PerpInstrument> findByExchangeAndIsActiveTrue(String exchange);

    List<PerpInstrument> findByIsWatchedTrue();

    /** Top 10 费率最高（DESC），仅 U本位（USDT/USD 结算） */
    @Query("SELECT p FROM PerpInstrument p WHERE p.exchange = :exchange AND p.isActive = true " +
           "AND p.latestFundingRate IS NOT NULL AND UPPER(p.quoteCurrency) IN ('USDT','USD') " +
           "ORDER BY p.latestFundingRate DESC LIMIT 10")
    List<PerpInstrument> findTop10HighByExchange(@Param("exchange") String exchange);

    /** Top 10 费率最低（ASC），仅 U本位 */
    @Query("SELECT p FROM PerpInstrument p WHERE p.exchange = :exchange AND p.isActive = true " +
           "AND p.latestFundingRate IS NOT NULL AND UPPER(p.quoteCurrency) IN ('USDT','USD') " +
           "ORDER BY p.latestFundingRate ASC LIMIT 10")
    List<PerpInstrument> findTop10LowByExchange(@Param("exchange") String exchange);

    /** 固定展示：按 exchange + symbol 列表查（BTC/ETH 精确匹配） */
    @Query("SELECT p FROM PerpInstrument p WHERE p.exchange = :exchange AND p.symbol IN :symbols AND p.isActive = true")
    List<PerpInstrument> findFeatured(@Param("exchange") String exchange, @Param("symbols") List<String> symbols);

    long countByExchangeAndIsActiveTrue(String exchange);
}
