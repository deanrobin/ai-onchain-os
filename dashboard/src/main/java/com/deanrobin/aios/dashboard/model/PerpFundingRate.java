package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter @Setter
@Entity
@Table(name = "perp_funding_rate",
       indexes = {
           @Index(name = "idx_exchange_symbol", columnList = "exchange, symbol"),
           @Index(name = "idx_fetched_at",      columnList = "fetched_at")
       })
public class PerpFundingRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String exchange;

    @Column(nullable = false, length = 100)
    private String symbol;

    @Column(name = "funding_rate", precision = 20, scale = 10)
    private BigDecimal fundingRate;

    @Column(name = "next_funding_time")
    private LocalDateTime nextFundingTime;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;
}
