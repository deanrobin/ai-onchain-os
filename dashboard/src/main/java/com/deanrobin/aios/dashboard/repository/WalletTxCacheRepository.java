package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.WalletTxCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface WalletTxCacheRepository extends JpaRepository<WalletTxCache, Long> {

    /** 按地址+链查最近交易，按时间倒序 */
    List<WalletTxCache> findByAddressAndChainIndexOrderByTxTimeDesc(String address, String chainIndex);

    /** 清理指定地址的旧缓存 */
    @Modifying @Transactional
    @Query("DELETE FROM WalletTxCache t WHERE t.address=:addr AND t.chainIndex=:chain")
    void deleteByAddressAndChainIndex(@Param("addr") String address, @Param("chain") String chainIndex);

    /** 清理 N 天前的过期缓存 */
    @Modifying @Transactional
    @Query("DELETE FROM WalletTxCache t WHERE t.createdAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
