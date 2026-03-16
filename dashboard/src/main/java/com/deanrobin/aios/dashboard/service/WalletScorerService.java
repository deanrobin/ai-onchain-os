package com.deanrobin.aios.dashboard.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 钱包综合评分（对齐 Python scorer.py）
 * 满分 100，公式：winRate×35 + pnl×30 + activity×20 + consistency×15 + bonus×5
 */
@Service
public class WalletScorerService {

    private static final double PNL_SCALE  = 50_000.0;  // $50k 对应满分
    private static final int    ACTIVE_MAX = 200;        // 活跃度满分参考交易次数

    /**
     * @param winRate       胜率 0~1
     * @param realizedPnl   已实现盈亏 USD
     * @param buyTxCount    买入次数
     * @param sellTxCount   卖出次数
     * @return 0~100 综合评分
     */
    public BigDecimal score(double winRate, double realizedPnl,
                            int buyTxCount, int sellTxCount) {
        // ── 1. 胜率分 (35分) ──────────────────────────────────
        double winScore = clamp(winRate, 0, 1) * 35.0;

        // ── 2. PnL 分 (30分) 对数缩放，负盈利归 0 ─────────────
        double pnlScore = 0;
        if (realizedPnl > 0) {
            pnlScore = Math.min(Math.log1p(realizedPnl) / Math.log1p(PNL_SCALE), 1.0) * 30.0;
        }

        // ── 3. 活跃度分 (20分) ────────────────────────────────
        int totalTx = buyTxCount + sellTxCount;
        double activityScore = Math.min((double) totalTx / ACTIVE_MAX, 1.0) * 20.0;

        // ── 4. 一致性分 (15分) 买卖比 0.3~0.7 最优 ───────────
        double consistencyScore = 0;
        if (totalTx > 0) {
            double buyRatio = (double) buyTxCount / totalTx;
            // 偏离 0.5 越远越低，0.5 满分
            double deviation = Math.abs(buyRatio - 0.5);
            consistencyScore = Math.max(0, (0.5 - deviation) / 0.5) * 15.0;
        }

        // ── 5. 精英加成 (5分) 胜率>60% 且 PnL>$10k ───────────
        double bonusScore = 0;
        if (winRate > 0.6 && realizedPnl > 10_000) bonusScore = 5.0;

        double total = winScore + pnlScore + activityScore + consistencyScore + bonusScore;
        return BigDecimal.valueOf(total).setScale(1, RoundingMode.HALF_UP);
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
