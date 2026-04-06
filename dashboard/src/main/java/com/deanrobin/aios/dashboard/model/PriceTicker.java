package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "price_ticker")
public class PriceTicker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** BTC / ETH / BNB / SOL */
    @Column(unique = true, nullable = false, length = 20)
    private String symbol;

    @Column(name = "price_usd", nullable = false, precision = 20, scale = 4)
    private BigDecimal priceUsd;

    /** 24H 涨跌幅 % (e.g. +2.31 or -1.45) */
    @Column(name = "change_24h", precision = 10, scale = 4)
    private BigDecimal change24h;

    /** 24H 交易量（USDT，来自 OKX ticker volCcy24h 字段） */
    @Column(name = "volume_24h", precision = 30, scale = 4)
    private BigDecimal volume24h;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
