package com.deanrobin.aios.common;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 聪明钱交易信号，由 signal-monitor 产生，传递给 trade-executor
 */
@Data
@Builder
public class TradeSignal {

    /** 触发信号的聪明钱地址 */
    private String smartMoneyAddress;

    /** 目标链 */
    private Chain chain;

    /** 买入 or 卖出 */
    private SignalType type;

    /** 代币合约地址 */
    private String tokenAddress;

    /** 代币 Symbol（尽量填充） */
    private String tokenSymbol;

    /** 聪明钱原始交易 hash */
    private String sourceTxHash;

    /** 聪明钱原始买入金额（USD 估值） */
    private BigDecimal sourceAmountUsd;

    /** 检测时间 */
    private LocalDateTime detectedAt;

    public enum SignalType {
        BUY, SELL
    }
}
