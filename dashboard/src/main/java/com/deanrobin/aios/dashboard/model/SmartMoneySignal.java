package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "smart_money_signal")
public class SmartMoneySignal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String chainIndex;
    private String tokenAddress;
    private String tokenSymbol;
    private String tokenName;
    private String tokenLogo;
    private String walletType;
    private Integer triggerWalletCount;
    @Column(columnDefinition = "TEXT")
    private String triggerWallets;
    private BigDecimal amountUsd;
    private BigDecimal priceAtSignal;
    private BigDecimal marketCapUsd;
    private BigDecimal soldRatioPercent;
    private LocalDateTime signalTime;
    private LocalDateTime createdAt;
}
