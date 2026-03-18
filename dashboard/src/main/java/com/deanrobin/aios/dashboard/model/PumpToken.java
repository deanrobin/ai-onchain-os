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

    /** new / survived */
    @Column(length = 20)
    private String status = "new";

    @Column(name = "checked_10m_at")
    private LocalDateTime checked10mAt;   // 10分钟阶段检查

    @Column(name = "checked_1h_at")
    private LocalDateTime checked1hAt;    // 1小时阶段检查
    private LocalDateTime lastCheckedAt;  // 24H阶段检查
    private java.math.BigDecimal currentMarketCap;
}
