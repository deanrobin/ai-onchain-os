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

    /** Top N 资金费率最高（DESC） */
    @Query("SELECT p FROM PerpInstrument p WHERE p.exchange = :exchange AND p.isActive = true AND p.latestFundingRate IS NOT NULL ORDER BY p.latestFundingRate DESC LIMIT 10")
    List<PerpInstrument> findTop10HighByExchange(@Param("exchange") String exchange);

    /** Top N 资金费率最低（ASC） */
    @Query("SELECT p FROM PerpInstrument p WHERE p.exchange = :exchange AND p.isActive = true AND p.latestFundingRate IS NOT NULL ORDER BY p.latestFundingRate ASC LIMIT 10")
    List<PerpInstrument> findTop10LowByExchange(@Param("exchange") String exchange);

    long countByExchangeAndIsActiveTrue(String exchange);
}
