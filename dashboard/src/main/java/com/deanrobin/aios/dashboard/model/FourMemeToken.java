package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "four_meme_token")
public class FourMemeToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @jakarta.persistence.Version
    private Integer version;

    private Long tokenId;

    @Column(unique = true)
    private String tokenAddress;

    private String name;
    private String shortName;   // symbol
    private String creator;

    @Column(precision = 20, scale = 8)
    private BigDecimal capBnb;          // 市值（BNB）

    @Column(precision = 10, scale = 4)
    private BigDecimal progress;        // 曲线进度 0-100

    @Column(precision = 30, scale = 12)
    private BigDecimal price;

    private Integer hold;               // 持有人数
    private String img;                 // 图片相对路径
    private Long createDate;            // 创建时间戳 ms

    @Column(nullable = false)
    private LocalDateTime receivedAt;

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
    private BigDecimal currentMarketCap;
}
