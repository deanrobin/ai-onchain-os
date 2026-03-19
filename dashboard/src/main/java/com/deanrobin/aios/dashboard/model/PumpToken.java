package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "pump_token")
public class PumpToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String mint;          // 合约地址

    private String name;
    private String symbol;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 500)
    private String imageUri;

    private String twitter;
    private String telegram;
    private String website;
    private String creator;

    private BigDecimal usdMarketCap;
    private Long createdTimestamp;

    @Column(nullable = false)
    private LocalDateTime receivedAt;

    // ── 实时数据（来自 pumpportal WSS）──────────────
    private java.math.BigDecimal marketCapSol;    // 市值(SOL)
    private java.math.BigDecimal progress;         // bonding curve 进度 0-100
    private java.math.BigDecimal vSolInCurve;      // vSolInBondingCurve
    private java.math.BigDecimal initialBuy;       // 初始买入 SOL

    /** new / survived */
    @Column(length = 20)
    private String status = "new";

    @Column(name = "checked_10m_at")  private LocalDateTime checked10mAt;
    @Column(name = "checked_20m_at")  private LocalDateTime checked20mAt;
    @Column(name = "checked_30m_at")  private LocalDateTime checked30mAt;
    @Column(name = "checked_45m_at")  private LocalDateTime checked45mAt;
    @Column(name = "checked_1h_at")   private LocalDateTime checked1hAt;
    @Column(name = "checked_4h_at")   private LocalDateTime checked4hAt;
    @Column(name = "checked_12h_at")  private LocalDateTime checked12hAt;
    private LocalDateTime lastCheckedAt;   // 24H
    private java.math.BigDecimal currentMarketCap;
}
