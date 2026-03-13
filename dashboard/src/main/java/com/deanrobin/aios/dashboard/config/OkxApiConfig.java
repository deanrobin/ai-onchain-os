package com.deanrobin.aios.dashboard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "okx")
public class OkxApiConfig {
    private String apiKey;
    private String apiSecret;
    private String passphrase;
    private String baseWww  = "https://www.okx.com";
    private String baseWeb3 = "https://web3.okx.com";
}
