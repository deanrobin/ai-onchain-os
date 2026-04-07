package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Binance U本位永续合约 24h 行情快照（每分钟更新，upsert 覆盖）。
 */
@Data
@Entity
@Table(name = "binance_ticker")
public class BinanceTicker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(name = "base_currency", length = 20)
    private String baseCurrency;

    @Column(name = "last_price", nullable = false, precision = 30, scale = 10)
    private BigDecimal lastPrice;

    /** 24h 涨跌幅，已是百分比值（如 2.50 表示 +2.50%） */
    @Column(name = "price_change_pct", nullable = false, precision = 12, scale = 4)
    private BigDecimal priceChangePct;

    /** 24h 成交额（USDT） */
    @Column(name = "quote_volume", nullable = false, precision = 30, scale = 4)
    private BigDecimal quoteVolume;

    @Column(name = "trade_count")
    private Integer tradeCount;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;
}
