package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合约持仓量(OI)快照 —— 每 5 分钟抓一次 watched 品种。
 * 保留 7 天，用于计算 15 分钟 / 4 小时持仓变化。
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

    /** 持仓量（基础货币单位，如 BTC 数量） */
    @Column(name = "oi_coin", precision = 30, scale = 4)
    private BigDecimal oiCoin;

    /** 持仓价值（USDT，= oiCoin × 当时价格） */
    @Column(name = "oi_usdt", precision = 30, scale = 4)
    private BigDecimal oiUsdt;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;
}
