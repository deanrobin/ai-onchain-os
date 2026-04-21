package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * BTC 做多策略信号：策略识别出机会后写入，随后触发飞书报警、网页显示和事后跟踪。
 *   status       —— OPEN / TP_HIT / SL_HIT / EXPIRED / CANCELLED
 *   alert_status —— PENDING / SENT / FAILED / SKIPPED
 */
@Data
@Entity
@Table(name = "btc_long_signal")
public class BtcLongSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signal_time", nullable = false)
    private LocalDateTime signalTime;

    @Column(name = "kline_id")
    private Long klineId;

    @Column(name = "strategy_name", nullable = false, length = 50)
    private String strategyName;

    @Column(name = "strategy_version", length = 20)
    private String strategyVersion;

    @Column(name = "entry_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "take_profit_price", precision = 20, scale = 8)
    private BigDecimal takeProfitPrice;

    @Column(name = "stop_loss_price", precision = 20, scale = 8)
    private BigDecimal stopLossPrice;

    @Column(name = "take_profit_pct", precision = 10, scale = 4)
    private BigDecimal takeProfitPct;

    @Column(name = "stop_loss_pct", precision = 10, scale = 4)
    private BigDecimal stopLossPct;

    @Column(name = "risk_reward", precision = 10, scale = 4)
    private BigDecimal riskReward;

    @Column(precision = 5, scale = 2)
    private BigDecimal confidence;

    @Column(name = "indicators_snapshot", columnDefinition = "json")
    private String indicatorsSnapshot;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_price", precision = 20, scale = 8)
    private BigDecimal closedPrice;

    @Column(name = "realized_pct", precision = 10, scale = 4)
    private BigDecimal realizedPct;

    @Column(name = "alert_status", nullable = false, length = 20)
    private String alertStatus;

    @Column(name = "alert_sent_at")
    private LocalDateTime alertSentAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
