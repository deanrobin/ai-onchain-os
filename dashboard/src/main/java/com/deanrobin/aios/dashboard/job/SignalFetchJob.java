package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.config.SmartMoneyJobConfig;
import com.deanrobin.aios.dashboard.model.SmartMoneySignal;
import com.deanrobin.aios.dashboard.model.SmartMoneyWallet;
import com.deanrobin.aios.dashboard.repository.SmartMoneySignalRepository;
import com.deanrobin.aios.dashboard.repository.SmartMoneyWalletRepository;
import com.deanrobin.aios.dashboard.service.OkxApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 🔄 定时抓取 OKX Smart Money 信号，写入 DB
 * 频率：smart-money.jobs.signal-fetch.interval-minutes（默认 5min）
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class SignalFetchJob {

    private static final String SIGNAL_PATH = "/api/v6/dex/market/signal/list";

    private final OkxApiClient             okxClient;
    private final SmartMoneyJobConfig      jobConfig;
    private final SmartMoneySignalRepository signalRepo;
    private final SmartMoneyWalletRepository walletRepo;
    private final ObjectMapper             mapper;

    /**
     * fixedDelayString 读 yml，单位 ms，启动 10s 后开始跑
     */
    @Scheduled(initialDelay = 30000, fixedDelay = 10000)
    public void run() {
        SmartMoneyJobConfig.SignalFetch cfg = jobConfig.getSignalFetch();
        if (!cfg.isEnabled()) {
            log.debug("⏸️ SignalFetchJob 已禁用，跳过");
            return;
        }

        log.info("⚡ SignalFetchJob 开始 chains={} walletTypes={}", cfg.getChains(), cfg.getWalletTypes());
        int totalNew = 0;
        Set<String> newWallets = new LinkedHashSet<>();

        for (String chain : cfg.chainArray()) {
            for (String wt : cfg.walletTypeArray()) {
                try {
                    totalNew += fetchChainWithRetry(chain.trim(), wt.trim(), cfg, newWallets);
                    Thread.sleep(2000); // 链间间隔 2s，避免 429
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.warn("⚠️ 抓取信号失败 chain={} wt={}: {}", chain, wt, e.getMessage());
                }
            }
        }

        log.info("✅ SignalFetchJob 完成 新信号={} 新钱包={}", totalNew, newWallets.size());
    }

    /** 带 429 退让重试，最多重试 2 次 */
    private int fetchChainWithRetry(String chain, String walletType,
                                    SmartMoneyJobConfig.SignalFetch cfg,
                                    Set<String> newWallets) throws InterruptedException {
        for (int attempt = 1; attempt <= 3; attempt++) {
            int result = fetchChain(chain, walletType, cfg, newWallets);
            if (result >= 0) return result;  // -1 表示 429，需重试
            long wait = 5000L * attempt;     // 5s / 10s / 15s
            log.warn("⚠️ 429 限速 chain={} 等待 {}ms 后重试 ({}/3)", chain, wait, attempt);
            Thread.sleep(wait);
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private int fetchChain(String chain, String walletType,
                           SmartMoneyJobConfig.SignalFetch cfg,
                           Set<String> newWallets) {
        Map<String, Object> body = Map.of(
            "chainIndex",    chain,
            "walletType",    walletType,
            "minAmountUsd",  cfg.getMinAmountUsd(),
            "addressCount",  "1",
            "limit",         "20"
        );

        Map<?, ?> resp = okxClient.postWeb3(SIGNAL_PATH, body);
        if (resp == null) return 0;
        String code = String.valueOf(resp.get("code"));
        // 429 → 返回 -1 触发重试
        if ("-1".equals(code) && String.valueOf(resp.get("msg")).contains("429")) return -1;
        if (!"0".equals(code)) {
            log.warn("⚠️ OKX 信号 API 异常 chain={} resp={}", chain, resp);
            return 0;
        }

        List<?> dataList = (List<?>) resp.get("data");
        if (dataList == null || dataList.isEmpty()) return 0;

        int saved = 0;
        for (Object item : dataList) {
            try {
                Map<String, Object> sig = (Map<String, Object>) item;
                // 不管信号是否重复，钱包地址都要提取
                extractWallets(sig, chain, newWallets);
                saved += saveSignal(sig, chain, walletType, newWallets);
            } catch (Exception e) {
                log.warn("⚠️ 解析信号失败: {}", e.getMessage());
            }
        }
        log.info("  📡 chain={} wt={} 获取={} 保存={} 新钱包={}", chain, walletType, dataList.size(), saved, newWallets.size());
        return saved;
    }

    @SuppressWarnings("unchecked")
    private int saveSignal(Map<String, Object> sig, String chain, String walletType,
                           Set<String> newWallets) {
        Map<String, Object> token = sig.containsKey("token")
            ? (Map<String, Object>) sig.get("token") : Map.of();

        String tokenAddr = String.valueOf(token.getOrDefault("tokenAddress",
                           sig.getOrDefault("tokenAddress", "")));
        if (tokenAddr.isBlank()) return 0;

        // 去重：同链同 token 同 walletType 最近 1h 内不重复保存
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        boolean exists = signalRepo.existsByChainIndexAndTokenAddressAndWalletTypeAndSignalTimeAfter(
                chain, tokenAddr, walletType, since);
        if (exists) return 0;

        SmartMoneySignal s = new SmartMoneySignal();
        s.setChainIndex(chain);
        s.setWalletType(walletType);
        s.setTokenAddress(tokenAddr);
        s.setTokenSymbol(String.valueOf(token.getOrDefault("symbol", "?")));
        s.setTokenName(String.valueOf(token.getOrDefault("tokenName", "")));
        s.setTokenLogo(String.valueOf(token.getOrDefault("logo", "")));
        s.setAmountUsd(toBd(sig.getOrDefault("amountUsd", "0")));
        s.setPriceAtSignal(toBd(token.getOrDefault("price", "0")));
        s.setMarketCapUsd(toBd(token.getOrDefault("marketCapUsd", "0")));
        s.setTriggerWalletCount(toInt(sig.getOrDefault("triggerWalletCount", "0")));
        // OKX soldRatioPercent 有时超出范围，截断到 [0, 9999]
        BigDecimal soldRatio = toBd(sig.get("soldRatioPercent"));
        if (soldRatio != null) {
            soldRatio = soldRatio.min(new BigDecimal("9999")).max(BigDecimal.ZERO);
        }
        s.setSoldRatioPercent(soldRatio);
        s.setSignalTime(LocalDateTime.now());
        s.setCreatedAt(LocalDateTime.now());

        // 保存钱包地址原始值（仅记录）
        Object twRaw = sig.get("triggerWalletAddress");
        if (twRaw != null) s.setTriggerWallets(String.valueOf(twRaw));

        signalRepo.save(s);
        return 1;
    }

    /**
     * 从信号里提取钱包地址，无论信号是否已存在都执行。
     * OKX 返回格式：triggerWalletAddress = "0xaaa,0xbbb,..."（逗号分隔字符串）
     */
    private void extractWallets(Map<String, Object> sig, String chain, Set<String> newWallets) {
        Object raw = sig.get("triggerWalletAddress");
        if (raw == null) return;
        String[] parts = String.valueOf(raw).split(",");
        for (String addr : parts) {
            addr = addr.strip();
            if (addr.length() >= 20) { // SOL 地址 ~44 字符, EVM ~42
                String key = chain + ":" + addr.toLowerCase();
                if (newWallets.add(key)) {
                    saveWalletIfAbsent(addr, chain);
                }
            }
        }
    }

    private void saveWalletIfAbsent(String address, String chain) {
        if (!walletRepo.existsByAddressAndChainIndex(address, chain)) {
            SmartMoneyWallet w = new SmartMoneyWallet();
            w.setAddress(address);
            w.setChainIndex(chain);
            w.setSource("okx_signal");
            w.setScore(BigDecimal.ZERO);
            w.setWinRate(BigDecimal.ZERO);
            w.setRealizedPnlUsd(BigDecimal.ZERO);
            w.setCreatedAt(LocalDateTime.now());
            w.setUpdatedAt(LocalDateTime.now());
            walletRepo.save(w);
        }
    }

    private BigDecimal toBd(Object v) {
        if (v == null) return null;
        try { return new BigDecimal(String.valueOf(v)); } catch (Exception e) { return BigDecimal.ZERO; }
    }
    private int toInt(Object v) {
        if (v == null) return 0;
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
}
