package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "wallet_tx_cache",
       indexes = {
           @Index(name = "idx_addr_chain", columnList = "address,chain_index"),
           @Index(name = "idx_tx_time",    columnList = "tx_time")
       })
public class WalletTxCache {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String address;

    @Column(name = "chain_index", length = 10)
    private String chainIndex;

    @Column(name = "tx_hash", length = 150)
    private String txHash;

    @Column(name = "tx_time")
    private Long txTime;

    @Column(name = "display_time", length = 30)
    private String displayTime;

    @Column(name = "type_label", length = 20)
    private String typeLabel;

    @Column(length = 50)
    private String symbol;

    @Column(length = 100)
    private String amount;

    private Boolean incoming;
    private Boolean success;

    @Column(name = "explorer_url", length = 250)
    private String explorerUrl;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
