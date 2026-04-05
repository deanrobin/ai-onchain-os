package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * K 线数据（OHLCV）。
 * 支持 5 个时间粒度：15m / 1H / 4H / 1D / 1W
 * 由 KlineFetchJob 每 30s 从 OKX 公开 API 拉取并 upsert。
 */
@Data
@Entity
@Table(name = "kline_bar",
       uniqueConstraints = @UniqueConstraint(name = "uk_symbol_bar_time",
                                             columnNames = {"symbol", "bar", "open_time"}))
public class KlineBar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** BTC / ETH / BNB / SOL */
    @Column(nullable = false, length = 20)
    private String symbol;

    /** 15m / 1H / 4H / 1D / 1W */
    @Column(nullable = false, length = 10)
    private String bar;

    @Column(name = "open_time", nullable = false)
    private LocalDateTime openTime;

    @Column(name = "open_price",  nullable = false, precision = 30, scale = 8)
    private BigDecimal openPrice;

    @Column(name = "high_price",  nullable = false, precision = 30, scale = 8)
    private BigDecimal highPrice;

    @Column(name = "low_price",   nullable = false, precision = 30, scale = 8)
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 30, scale = 8)
    private BigDecimal closePrice;

    /** 成交量（基础货币，如 BTC 数量） */
    @Column(nullable = false, precision = 30, scale = 4)
    private BigDecimal volume;

    /** 成交额（USDT） */
    @Column(name = "volume_usdt", precision = 30, scale = 4)
    private BigDecimal volumeUsdt;

    /** 0 = 未收盘（进行中），1 = 已收盘 */
    @Column(nullable = false, columnDefinition = "TINYINT(1) DEFAULT 0")
    private boolean confirmed;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
