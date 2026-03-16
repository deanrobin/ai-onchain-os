package com.deanrobin.aios.dashboard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * smart-money.jobs.* 配置项绑定
 */
@Data
@Component
@ConfigurationProperties(prefix = "smart-money.jobs")
public class SmartMoneyJobConfig {

    private SignalFetch signalFetch = new SignalFetch();
    private WalletAnalyze walletAnalyze = new WalletAnalyze();

    @Data
    public static class SignalFetch {
        private boolean enabled = true;
        private int intervalMinutes = 5;
        private String chains = "1,56,8453,501";
        private String walletTypes = "1";
        private String minAmountUsd = "5000";

        public String[] chainArray() {
            return chains.split(",");
        }
        public String[] walletTypeArray() {
            return walletTypes.split(",");
        }
    }

    @Data
    public static class WalletAnalyze {
        private boolean enabled = true;
        private int intervalMinutes = 30;
        private String timeFrame = "3";
        private int maxWallets = 100;
    }
}
