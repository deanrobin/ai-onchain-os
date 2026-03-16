package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "smart_money_wallet")
public class SmartMoneyWallet {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String address;
    private String chainIndex;
    private String label;
    private BigDecimal score;
    private BigDecimal winRate;
    private BigDecimal realizedPnlUsd;
    private Integer buyTxCount;
    private Integer sellTxCount;
    private BigDecimal avgBuyValueUsd;
    private String source;

    /** OKX portfolio overview 原始 JSON（Web 按需抓取，缓存用） */
    @Column(name = "overview_json", columnDefinition = "TEXT")
    private String overviewJson;

    /** overview 最后更新时间，用于判断缓存是否过期 */
    @Column(name = "overview_updated_at")
    private LocalDateTime overviewUpdatedAt;

    private LocalDateTime lastAnalyzedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
