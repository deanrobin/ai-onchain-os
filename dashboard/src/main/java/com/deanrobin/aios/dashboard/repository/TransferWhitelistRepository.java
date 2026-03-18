package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.TransferWhitelist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransferWhitelistRepository extends JpaRepository<TransferWhitelist, Long> {

    /**
     * 精确匹配地址（BSC 调用前请先 toLowerCase()；SOL 原样传入）
     */
    Optional<TransferWhitelist> findByAddress(String address);
}
