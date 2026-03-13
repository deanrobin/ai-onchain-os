package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.SmartMoneyWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface SmartMoneyWalletRepository extends JpaRepository<SmartMoneyWallet, Long> {

    @Query(value = "SELECT * FROM smart_money_wallet ORDER BY score DESC LIMIT :limit", nativeQuery = true)
    List<SmartMoneyWallet> findTopByScoreDesc(@Param("limit") int limit);

    List<SmartMoneyWallet> findByChainIndexOrderByScoreDesc(String chainIndex);
}
