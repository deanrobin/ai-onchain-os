package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 代币供应量快照（来自 CoinGecko，12H 刷新一次）。
 * 用于计算合约市值 = 价格 × 总量，流通市值 = 价格 × 流通量。
 */
@Getter @Setter
@Entity
@Table(name = "perp_supply_snapshot")
public class PerpSupplySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 基础货币，如 BTC / ETH / SOL */
    @Column(name = "base_currency", nullable = false, length = 20)
    private String baseCurrency;

    /** CoinGecko coin ID，如 bitcoin / ethereum */
    @Column(name = "coingecko_id", length = 100)
    private String coingeckoId;

    /** 流通量 */
    @Column(name = "circulating_supply", precision = 40, scale = 4)
    private BigDecimal circulatingSupply;

    /** 总量（部分代币无上限时为 null） */
    @Column(name = "total_supply", precision = 40, scale = 4)
    private BigDecimal totalSupply;

    /** 最大供应量（硬顶，如 BTC=21000000） */
    @Column(name = "max_supply", precision = 40, scale = 4)
    private BigDecimal maxSupply;

    /** 数据获取时间 */
    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
