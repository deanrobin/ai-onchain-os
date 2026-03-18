package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 转账白名单表
 * BSC 地址以小写存储；SOL 地址原样存储（区分大小写）
 */
@Data
@Entity
@Table(name = "transfer_whitelist")
public class TransferWhitelist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String address;

    @Column(length = 200)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
