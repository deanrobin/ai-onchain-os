package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.SmartMoneySignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SmartMoneySignalRepository extends JpaRepository<SmartMoneySignal, Long> {

    @Query(value = "SELECT * FROM smart_money_signal ORDER BY signal_time DESC LIMIT :limit", nativeQuery = true)
    List<SmartMoneySignal> findRecent(@Param("limit") int limit);

    @Query(value = "SELECT * FROM smart_money_signal WHERE chain_index=:chain ORDER BY signal_time DESC LIMIT :limit", nativeQuery = true)
    List<SmartMoneySignal> findRecentByChain(@Param("chain") String chain, @Param("limit") int limit);

    /** 去重检查：1h 内同链同 token 同类型不重复存 */
    boolean existsByChainIndexAndTokenAddressAndWalletTypeAndSignalTimeAfter(
            String chainIndex, String tokenAddress, String walletType, LocalDateTime signalTime);
}
