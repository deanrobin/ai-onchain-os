package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "onchain_holder_snapshot",
       indexes = {
           @Index(name = "idx_watch_wallet", columnList = "watch_id,wallet_addr"),
           @Index(name = "idx_snapped_at",   columnList = "snapped_at")
       })
public class OnchainHolderSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "watch_id", nullable = false)
    private Long watchId;

    @Column(name = "wallet_addr", nullable = false, length = 42)
    private String walletAddr;

    /** 原始余额（未除精度的整数值）*/
    @Column(name = "balance_raw", nullable = false, precision = 40, scale = 0)
    private BigDecimal balanceRaw;

    /** 余额（已除精度）*/
    @Column(name = "balance_token", nullable = false, precision = 30, scale = 6)
    private BigDecimal balanceToken;

    /** 快照时代币 USD 价格 */
    @Column(name = "price_usd", precision = 20, scale = 8)
    private BigDecimal priceUsd;

    /** 折算 USD 市值 */
    @Column(name = "value_usd", precision = 20, scale = 2)
    private BigDecimal valueUsd;

    @Column(name = "block_number", nullable = false)
    private Long blockNumber;

    @Column(name = "snapped_at", nullable = false)
    private LocalDateTime snappedAt = LocalDateTime.now();
}
