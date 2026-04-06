package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "onchain_watch",
       indexes = {
           @Index(name = "idx_network", columnList = "network"),
           @Index(name = "idx_active",  columnList = "is_active")
       })
public class OnchainWatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 代币名称，如 STO */
    @Column(name = "token_name", nullable = false, length = 50)
    private String tokenName;

    /** 合约地址 0x... */
    @Column(name = "contract_addr", nullable = false, length = 42)
    private String contractAddr;

    /** ETH | BSC */
    @Column(name = "network", nullable = false, length = 10)
    private String network;

    /** ERC20 小数位，首次查链获取，默认 18 */
    @Column(name = "token_decimals", nullable = false)
    private int tokenDecimals = 18;

    /** TOKEN = 按代币数量比对；USD = 按折算美元比对 */
    @Column(name = "threshold_mode", nullable = false, length = 10)
    private String thresholdMode = "USD";

    /** TOKEN 模式下的数量阈值 */
    @Column(name = "threshold_amount", precision = 30, scale = 4)
    private BigDecimal thresholdAmount;

    /** USD 模式下的金额阈值（默认 50000）*/
    @Column(name = "threshold_usd", nullable = false, precision = 20, scale = 2)
    private BigDecimal thresholdUsd = new BigDecimal("50000");

    /** 关注地址列表，JSON 存储 */
    @Column(name = "watched_addrs", nullable = false, columnDefinition = "JSON")
    @Convert(converter = StringListJsonConverter.class)
    private List<String> watchedAddrs;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
