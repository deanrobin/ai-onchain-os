package com.deanrobin.aios.dashboard.vo;

import lombok.Data;

@Data
public class TradeResult {

    private boolean success;
    private String  txHash;
    private String  errorMsg;

    public static TradeResult success(String txHash) {
        TradeResult r = new TradeResult();
        r.success  = true;
        r.txHash   = txHash;
        return r;
    }

    public static TradeResult error(String msg) {
        TradeResult r = new TradeResult();
        r.success  = false;
        r.errorMsg = msg;
        return r;
    }
}
