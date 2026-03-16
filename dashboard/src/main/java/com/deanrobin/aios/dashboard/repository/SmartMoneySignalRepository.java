package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.SmartMoneySignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SmartMoneySignalRepository extends JpaRepository<SmartMoneySignal, Long> {

    @Query(value = "SELECT * FROM smart_money_signal WHERE (market_cap_usd IS NULL OR market_cap_usd=0 OR (market_cap_usd>=10000 AND amount_usd/market_cap_usd<=0.5)) ORDER BY signal_time DESC LIMIT :limit", nativeQuery = true)
    List<SmartMoneySignal> findRecent(@Param("limit") int limit);

    @Query(value = "SELECT * FROM smart_money_signal WHERE chain_index=:chain AND (market_cap_usd IS NULL OR market_cap_usd=0 OR (market_cap_usd>=10000 AND amount_usd/market_cap_usd<=0.5)) ORDER BY signal_time DESC LIMIT :limit", nativeQuery = true)
    List<SmartMoneySignal> findRecentByChain(@Param("chain") String chain, @Param("limit") int limit);

    /** 查最近一条同链同 token 记录（用于判断是否需要更新） */
    @Query(value = "SELECT * FROM smart_money_signal WHERE chain_index=:chain AND token_address=:addr AND wallet_type=:wt ORDER BY signal_time DESC LIMIT 1", nativeQuery = true)
    java.util.Optional<SmartMoneySignal> findLatest(@Param("chain") String chain, @Param("addr") String addr, @Param("wt") String wt);
}
