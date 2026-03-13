package com.deanrobin.aios.common;

/**
 * 支持的链枚举
 * OKX chainIndex 参考：https://web3.okx.com/zh-hans/onchainos/dev-docs
 */
public enum Chain {
    ETH("1", "Ethereum"),
    BSC("56", "BNB Chain"),
    BASE("8453", "Base"),
    SOLANA("501", "Solana");

    /** OKX chainIndex */
    public final String chainIndex;
    public final String name;

    Chain(String chainIndex, String name) {
        this.chainIndex = chainIndex;
        this.name = name;
    }
}
