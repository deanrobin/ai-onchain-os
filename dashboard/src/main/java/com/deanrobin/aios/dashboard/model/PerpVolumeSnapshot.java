package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合约成交量异动持仓快照。
 * 当 1H 合约成交额 > 20期均量 × 阈值时由 ContractVolumeAlertJob 写入，
 * 并在 24H / 48H 后自动跟进报警。
 */
@Data
@Entity
@Table(name = "perp_volume_snapshot")
public class PerpVolumeSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** BTC / ETH / BNB / SOL */
    @Column(nullable = false, length = 20)
    private String symbol;

    /** K 线周期（1H） */
    @Column(nullable = false, length = 10)
    private String bar;

    /** 触发时合约成交额（USDT） */
    @Column(name = "volume_usdt", nullable = false, precision = 30, scale = 4)
    private BigDecimal volumeUsdt;

    /** 20 期均量（USDT） */
    @Column(name = "avg_volume_usdt", nullable = false, precision = 30, scale = 4)
    private BigDecimal avgVolumeUsdt;

    /** 成交量倍数（volume / avg） */
    @Column(name = "volume_ratio", nullable = false, precision = 10, scale = 4)
    private BigDecimal volumeRatio;

    /** 触发时收盘价（USDT） */
    @Column(name = "close_price", precision = 30, scale = 8)
    private BigDecimal closePrice;

    /** 持仓量 OI（USDT，来自 Binance openInterestHist） */
    @Column(name = "oi_usdt", precision = 30, scale = 4)
    private BigDecimal oiUsdt;

    /** 多空账户比（longShortRatio，>1 代表做多账户更多） */
    @Column(name = "ls_ratio", precision = 10, scale = 4)
    private BigDecimal lsRatio;

    /** 做多账户占比（0~1） */
    @Column(name = "long_pct", precision = 10, scale = 4)
    private BigDecimal longPct;

    /** 做空账户占比（0~1） */
    @Column(name = "short_pct", precision = 10, scale = 4)
    private BigDecimal shortPct;

    /** 资金费率（来自 Binance PerpInstrument 缓存） */
    @Column(name = "funding_rate", precision = 20, scale = 10)
    private BigDecimal fundingRate;

    /** 快照时间 */
    @Column(name = "snapped_at", nullable = false)
    private LocalDateTime snappedAt;

    /** 24H 跟进是否已发送 */
    @Column(name = "followup_24h_done", nullable = false, columnDefinition = "TINYINT(1) DEFAULT 0")
    private boolean followup24hDone;

    /** 48H 跟进是否已发送 */
    @Column(name = "followup_48h_done", nullable = false, columnDefinition = "TINYINT(1) DEFAULT 0")
    private boolean followup48hDone;
}
