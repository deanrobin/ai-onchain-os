package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OI 突破告警记录 + 特别关注时间窗口。
 *
 * 当某品种持仓量 USD >= 5000万（5×10^7）时写入一条记录：
 *   - alerted_at  = 告警时刻
 *   - watch_until = alerted_at + 48h（特别关注截止时间）
 *
 * 48h 到期后若 OI 仍 >= 阈值，PerpOiJob 重新写入一条记录（自动续期）。
 */
@Getter @Setter
@Entity
@Table(name = "perp_oi_alert")
public class PerpOiAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String exchange;

    @Column(nullable = false, length = 100)
    private String symbol;

    /** 触发时的持仓量 USD */
    @Column(name = "oi_usd", precision = 30, scale = 4)
    private BigDecimal oiUsd;

    @Column(name = "alerted_at", nullable = false)
    private LocalDateTime alertedAt;

    /** 特别关注截止时间 = alertedAt + 48h */
    @Column(name = "watch_until", nullable = false)
    private LocalDateTime watchUntil;
}
