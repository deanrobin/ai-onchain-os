package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 特别关注品种 5 分钟快照。
 * 处于特别关注期（perp_oi_alert.watch_until > now）的品种，
 * 由 PerpWatchJob 每 5 分钟写入一条，供事后复盘。
 */
@Getter @Setter
@Entity
@Table(name = "perp_oi_watch_snapshot")
public class PerpOiWatchSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String exchange;

    @Column(nullable = false, length = 100)
    private String symbol;

    /** 当前价格（USD）；从 price_ticker 读取，基础货币不在表中则为 null */
    @Column(name = "price_usd", precision = 30, scale = 8)
    private BigDecimal priceUsd;

    /** 24h 涨跌幅（%），如 +3.17 表示涨 3.17% */
    @Column(name = "change_24h", precision = 10, scale = 4)
    private BigDecimal change24h;

    /** 持仓量 USD 估算（来自 perp_instrument.latest_oi_usd） */
    @Column(name = "oi_usd", precision = 30, scale = 4)
    private BigDecimal oiUsd;

    @Column(name = "snapped_at", nullable = false)
    private LocalDateTime snappedAt;
}
