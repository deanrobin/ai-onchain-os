package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "pump_market_cap_snapshot")
public class PumpMarketCapSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String mint;
    private String symbol;
    private String name;
    private BigDecimal marketCapUsd;

    @Column(nullable = false)
    private LocalDateTime checkedAt;
}
