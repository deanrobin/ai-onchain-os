package com.deanrobin.cryptoqmt.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * BTCUSDT 15m K 线 + 技术指标历史 (MA20 / MA120 / MACD / RSI21)。
 * open_time 为 K 线开盘时间,UNIQUE,便于 upsert。
 */
@Data
@Entity
@Table(name = "btc_kline_15m",
        uniqueConstraints = @UniqueConstraint(name = "uk_open_time", columnNames = "open_time"))
public class BtcKline15m {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "open_time", nullable = false)
    private LocalDateTime openTime;

    @Column(name = "open_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal openPrice;

    @Column(name = "high_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal closePrice;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal volume;

    @Column(name = "quote_volume", precision = 30, scale = 4)
    private BigDecimal quoteVolume;

    @Column(name = "trade_count")
    private Integer tradeCount;

    @Column(precision = 20, scale = 8)
    private BigDecimal ma20;

    @Column(precision = 20, scale = 8)
    private BigDecimal ma120;

    @Column(name = "macd_dif", precision = 20, scale = 8)
    private BigDecimal macdDif;

    @Column(name = "macd_dea", precision = 20, scale = 8)
    private BigDecimal macdDea;

    @Column(name = "macd_hist", precision = 20, scale = 8)
    private BigDecimal macdHist;

    @Column(precision = 10, scale = 4)
    private BigDecimal rsi21;

    @Column(length = 20)
    private String source;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
