package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.config.SmartMoneyJobConfig;
import com.deanrobin.aios.dashboard.model.SmartMoneyWallet;
import com.deanrobin.aios.dashboard.repository.SmartMoneyWalletRepository;
import com.deanrobin.aios.dashboard.service.OkxApiClient;
import com.deanrobin.aios.dashboard.service.WalletScorerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    private final OkxApiClient              okxClient;
    private final SmartMoneyJobConfig       jobConfig;
    private final SmartMoneyWalletRepository walletRepo;
    private final WalletScorerService       scorer;

    @Scheduled(
        initialDelayString = "#{${smart-money.jobs.wallet-analyze.interval-minutes:30} * 60000}",
        fixedDelayString   = "#{${smart-money.jobs.wallet-analyze.interval-minutes:30} * 60000}"
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

        log.info("🧠 WalletAnalyzeJob 开始 待分析={}", wallets.size());
        int ok = 0, fail = 0;

        for (SmartMoneyWallet w : wallets) {
            try {
                analyze(w, cfg.getTimeFrame());
                ok++;
                Thread.sleep(300); // 限速
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
        Map<?, ?> resp = okxClient.getWeb3(OVERVIEW_PATH, Map.of(
            "chainIndex", w.getChainIndex(),
            "address",    w.getAddress(),
            "timeFrame",  timeFrame
        ));
        if (resp == null || !"0".equals(String.valueOf(resp.get("code")))) return;

        Map<?, ?> data = extractData(resp);
        if (data == null) return;

        double winRate = toDouble(data.get("winRate")) / 100.0; // OKX 返回 0-100
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

        walletRepo.save(w);
        log.info("  📊 {} chain={} score={} win={}% pnl=${}",
                w.getAddress().substring(0, 10) + "...",
                w.getChainIndex(),
                w.getScore(),
                String.format("%.1f", winRate * 100),
                String.format("%.0f", pnlUsd));
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
