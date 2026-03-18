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

    private LocalDateTime lastCheckedAt;
    private java.math.BigDecimal currentMarketCap;
}
