package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合约持仓量(OI)快照。
 * Binance watched 品种每 5 分钟采集；OKX / Hyperliquid 每 15 分钟采集。
 */
@Getter @Setter
@Entity
@Table(name = "perp_open_interest")
public class PerpOpenInterest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String exchange;

    @Column(nullable = false, length = 100)
    private String symbol;

    /** 持仓量（基础货币单位，如 BTC 数量；Hyperliquid 为 USD 计价） */
    @Column(name = "oi_coin", precision = 30, scale = 4)
    private BigDecimal oiCoin;

    /** 持仓价值（USDT，= oiCoin × 当时价格） */
    @Column(name = "oi_usdt", precision = 30, scale = 4)
    private BigDecimal oiUsdt;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;
}
