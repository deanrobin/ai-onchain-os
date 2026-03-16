package com.deanrobin.aios.dashboard.vo;

import lombok.Data;

/** 交易记录展示对象 —— 模板只做 ${tx.xxx} 纯展示 */
@Data
public class TxRowVO {
    private String displayTime;   // "03-16 16:30:00"
    private String typeLabel;     // "Token转账" / "主链币" / "合约调用"
    private String symbol;        // "USDT"
    private String amount;        // "1234.56"
    private boolean incoming;     // true=收入 false=支出
    private boolean success;      // true=成功 false=失败
    private String txHash;
    private String txHashShort;   // 前12位...
    private String explorerUrl;
}
