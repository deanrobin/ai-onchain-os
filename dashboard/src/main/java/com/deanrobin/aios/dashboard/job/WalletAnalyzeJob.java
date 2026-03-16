package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.config.SmartMoneyJobConfig;
import com.deanrobin.aios.dashboard.model.SmartMoneyWallet;
import com.deanrobin.aios.dashboard.model.WalletTxCache;
import com.deanrobin.aios.dashboard.repository.SmartMoneyWalletRepository;
import com.deanrobin.aios.dashboard.repository.WalletTxCacheRepository;
import com.deanrobin.aios.dashboard.service.OkxApiClient;
import com.deanrobin.aios.dashboard.service.WalletScorerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 🧠 定时分析钱包 PnL / 胜率 / 评分，更新 DB
 * 频率：smart-money.jobs.wallet-analyze.interval-minutes（默认 30min）
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class WalletAnalyzeJob {

    private static final String OVERVIEW_PATH = "/api/v6/dex/market/portfolio/overview";

    private static final String TX_PATH = "/api/v6/dex/post-transaction/transactions-by-address";
    private static final DateTimeFormatter TX_FMT =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private final OkxApiClient              okxClient;
    private final SmartMoneyJobConfig       jobConfig;
    private final SmartMoneyWalletRepository walletRepo;
    private final WalletTxCacheRepository   txCacheRepo;
    private final WalletScorerService       scorer;
    private final ObjectMapper              objectMapper;

    @Scheduled(
        initialDelayString = "60000",
        fixedDelayString   = "#{${smart-money.jobs.wallet-analyze.interval-minutes:5} * 60000}"
    )
    public void run() {
        SmartMoneyJobConfig.WalletAnalyze cfg = jobConfig.getWalletAnalyze();
        if (!cfg.isEnabled()) {
            log.debug("⏸️ WalletAnalyzeJob 已禁用，跳过");
            return;
        }

        // 优先分析最久未更新的钱包
        List<SmartMoneyWallet> wallets = walletRepo.findAllByOrderByLastAnalyzedAtAsc(
                PageRequest.of(0, cfg.getMaxWallets()));

        // 过滤掉 2 小时内已分析过的（防止重复打 API）
        LocalDateTime freshCutoff = LocalDateTime.now().minusMinutes(15); // 15分钟内分析过的跳过
        long skip = wallets.stream().filter(w -> w.getLastAnalyzedAt() != null && w.getLastAnalyzedAt().isAfter(freshCutoff)).count();
        wallets = wallets.stream().filter(w -> w.getLastAnalyzedAt() == null || w.getLastAnalyzedAt().isBefore(freshCutoff))
                         .collect(java.util.stream.Collectors.toList());

        log.info("🧠 WalletAnalyzeJob 开始 待分析={} 跳过(15min内已分析)={}", wallets.size(), skip);
        int ok = 0, fail = 0;

        for (SmartMoneyWallet w : wallets) {
            try {
                analyze(w, cfg.getTimeFrame());
                ok++;
                Thread.sleep(1000); // 限速：每个钱包 1s 间隔
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                fail++;
                log.warn("⚠️ 分析钱包失败 addr={} chain={}: {}",
                        w.getAddress(), w.getChainIndex(), e.getMessage());
            }
        }

        log.info("✅ WalletAnalyzeJob 完成 成功={} 失败={}", ok, fail);
    }

    @SuppressWarnings("unchecked")
    private void analyze(SmartMoneyWallet w, String timeFrame) {
        // 1. 抓 overview
        Map<?, ?> resp = okxClient.getWeb3(OVERVIEW_PATH, Map.of(
            "chainIndex",    w.getChainIndex(),
            "walletAddress", w.getAddress(),   // OKX 参数名是 walletAddress
            "timeFrame",     timeFrame
        ));
        if (resp == null || !"0".equals(String.valueOf(resp.get("code")))) return;

        Map<?, ?> data = extractData(resp);
        if (data == null) return;

        double winRate = toDouble(data.get("winRate")) / 100.0;
        double pnlUsd  = toDouble(data.get("realizedPnlUsd"));
        int    buyCnt  = toInt(data.get("buyTxCount"));
        int    sellCnt = toInt(data.get("sellTxCount"));

        w.setWinRate(BigDecimal.valueOf(winRate));
        w.setRealizedPnlUsd(BigDecimal.valueOf(pnlUsd));
        w.setBuyTxCount(buyCnt);
        w.setSellTxCount(sellCnt);
        w.setAvgBuyValueUsd(toBd(data.get("avgBuyValueUsd")));
        w.setScore(scorer.score(winRate, pnlUsd, buyCnt, sellCnt));
        w.setLastAnalyzedAt(LocalDateTime.now());
        w.setUpdatedAt(LocalDateTime.now());

        // 存 overview JSON 到 DB，Web 层直接读，不再调 OKX
        try { w.setOverviewJson(objectMapper.writeValueAsString(data)); }
        catch (Exception ignored) {}

        // 2. 抓 tx history 并缓存到 DB
        cacheTxHistory(w);

        walletRepo.save(w);
        log.info("  📊 {} chain={} score={} win={}% pnl=${}",
                w.getAddress().substring(0, 10) + "...",
                w.getChainIndex(),
                w.getScore(),
                String.format("%.1f", winRate * 100),
                String.format("%.0f", pnlUsd));
    }

    @SuppressWarnings("unchecked")
    private void cacheTxHistory(SmartMoneyWallet w) {
        try {
            Map<?, ?> txResp = okxClient.getWeb3(TX_PATH, Map.of(
                "address", w.getAddress(),
                "chains",  w.getChainIndex(),
                "limit",   "50"
            ));
            if (txResp == null || !"0".equals(String.valueOf(txResp.get("code")))) return;

            Object txData = txResp.get("data");
            List<?> txList = null;
            if (txData instanceof Map<?,?> txMap) txList = (List<?>) txMap.get("transactions");
            else if (txData instanceof List<?> l) txList = l;
            if (txList == null || txList.isEmpty()) return;

            String addrLower = w.getAddress().toLowerCase();
            String chain = w.getChainIndex();
            String explorer = "56".equals(chain) ? "https://bscscan.com/tx/"
                            : "501".equals(chain) ? "https://solscan.io/tx/"
                            : "https://etherscan.io/tx/";

            // 先清旧缓存
            txCacheRepo.deleteByAddressAndChainIndex(w.getAddress(), chain);

            List<WalletTxCache> rows = new ArrayList<>();
            for (Object item : txList) {
                if (!(item instanceof Map<?,?> tx)) continue;
                WalletTxCache row = new WalletTxCache();
                row.setAddress(w.getAddress());
                row.setChainIndex(chain);

                Object tsObj = tx.get("txTime");
                if (tsObj != null) {
                    try {
                        long ts = Long.parseLong(String.valueOf(tsObj));
                        row.setTxTime(ts);
                        row.setDisplayTime(TX_FMT.format(Instant.ofEpochMilli(ts)));
                    } catch (Exception e) { row.setDisplayTime("—"); }
                }

                Object itypeObj = tx.get("itype");
                String itype = itypeObj != null ? String.valueOf(itypeObj) : "";
                row.setTypeLabel("2".equals(itype) ? "Token转账" : "0".equals(itype) ? "主链币" : "合约调用");

                Object sym = tx.get("symbol");
                row.setSymbol(sym != null ? String.valueOf(sym) : "—");
                Object amt = tx.get("amount");
                row.setAmount(amt != null ? String.valueOf(amt) : "—");

                // 方向
                boolean incoming = false;
                Object toObj = tx.get("to");
                if (toObj instanceof List<?> toList && !toList.isEmpty()) {
                    Object first = toList.get(0);
                    if (first instanceof Map<?,?> toMap) {
                        Object toAddr = toMap.get("address");
                        incoming = toAddr != null && String.valueOf(toAddr).toLowerCase().contains(addrLower);
                    }
                }
                row.setIncoming(incoming);
                row.setSuccess("success".equals(tx.get("txStatus")));

                Object hash = tx.get("txHash");
                String hashStr = hash != null ? String.valueOf(hash) : "";
                row.setTxHash(hashStr);
                row.setExplorerUrl(explorer + hashStr);
                row.setCreatedAt(LocalDateTime.now());
                rows.add(row);
            }
            txCacheRepo.saveAll(rows);
        } catch (Exception e) {
            log.warn("⚠️ 缓存 tx history 失败 addr={}: {}", w.getAddress().substring(0,10), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> extractData(Map<?, ?> resp) {
        Object d = resp.get("data");
        if (d instanceof List<?> list && !list.isEmpty()) {
            return (Map<?, ?>) list.get(0);
        }
        if (d instanceof Map<?, ?> m) return m;
        return null;
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
    private int toInt(Object v) {
        if (v == null) return 0;
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
    private BigDecimal toBd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        try { return new BigDecimal(String.valueOf(v)); } catch (Exception e) { return BigDecimal.ZERO; }
    }
}
