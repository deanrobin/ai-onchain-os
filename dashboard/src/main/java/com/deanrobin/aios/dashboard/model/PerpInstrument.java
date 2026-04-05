package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter
@Entity
@Table(name = "perp_instrument")
public class PerpInstrument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** OKX / BINANCE / HYPERLIQUID */
    @Column(nullable = false, length = 20)
    private String exchange;

    /** 原始交易对代码，e.g. BTC-USDT-SWAP */
    @Column(nullable = false, length = 100)
    private String symbol;

    @Column(name = "base_currency", length = 20)
    private String baseCurrency;

    @Column(name = "quote_currency", length = 20)
    private String quoteCurrency;

    @Column(name = "is_watched")
    private Boolean isWatched = false;

    @Column(name = "is_active")
    private Boolean isActive = true;

    /** 最新资金费率（由 FundingRateJob 更新，加速页面查询） */
    @Column(name = "latest_funding_rate", precision = 20, scale = 10)
    private BigDecimal latestFundingRate;

    @Column(name = "latest_funding_updated_at")
    private LocalDateTime latestFundingUpdatedAt;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

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
