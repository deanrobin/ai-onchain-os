package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.SmartMoneyWallet;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SmartMoneyWalletRepository extends JpaRepository<SmartMoneyWallet, Long> {

    @Query(value = "SELECT * FROM smart_money_wallet ORDER BY score DESC LIMIT :limit", nativeQuery = true)
    List<SmartMoneyWallet> findTopByScoreDesc(@Param("limit") int limit);

    List<SmartMoneyWallet> findByChainIndexOrderByScoreDesc(String chainIndex);

    /** 去重：地址+链已存在则跳过 */
    boolean existsByAddressAndChainIndex(String address, String chainIndex);

    /** 最久未分析优先：按 lastAnalyzedAt asc（null 排最前） */
    @Query("SELECT w FROM SmartMoneyWallet w ORDER BY COALESCE(w.lastAnalyzedAt, '1970-01-01') ASC")
    List<SmartMoneyWallet> findAllByOrderByLastAnalyzedAtAsc(Pageable pageable);

    /** 按地址+链查单条记录 */
    java.util.Optional<SmartMoneyWallet> findByAddressAndChainIndex(String address, String chainIndex);
}
