package com.deanrobin.aios.dashboard.vo;

import lombok.Data;
import java.util.List;
import java.util.Map;

/** 钱包概览展示对象 —— 所有数值/样式在 Java 层计算好 */
@Data
public class OverviewVO {
    // 胜率
    private String winRatePct;      // "52.3%"
    private boolean winRateGood;    // >=50% → green

    // 已实现盈亏
    private String pnlDisplay;      // "+$12,345" / "-$1,234"
    private boolean pnlPositive;

    // Top3 盈利
    private String top3Display;     // "$23,456"

    // 买卖次数
    private String txCountDisplay;  // "45 / 32"

    // 平均买入
    private String avgBuyDisplay;   // "$2,345"

    // 偏好市值
    private String preferredMarketCap;

    // 盈亏分布
    private int over500;
    private int zero500;
    private int zeroMinus50;
    private int overMinus50;
    private boolean hasPnlDist;

    // Top3 代币列表
    private List<TopTokenVO> topTokens;

    @Data
    public static class TopTokenVO {
        private String symbol;
        private String pnlDisplay;   // "$12,345"
        private String pctDisplay;   // "+45.2%"
        private boolean positive;
        private String medal;        // "🥇" / "🥈" / "🥉"
        private String cardClass;    // "sc-yellow" / "sc-blue" / "sc-cyan"
    }

    /** 从 OKX 原始 Map 构建 VO */
    @SuppressWarnings("unchecked")
    public static OverviewVO from(Map<String, Object> raw) {
        OverviewVO v = new OverviewVO();
        if (raw == null || raw.isEmpty()) return v;

        double wr  = toDouble(raw.get("winRate"));
        double pnl = toDouble(raw.get("realizedPnlUsd"));
        double top3= toDouble(raw.get("top3PnlTokenSumUsd"));
        double avg = toDouble(raw.get("avgBuyValueUsd"));
        int buy    = (int) toDouble(raw.get("buyTxCount"));
        int sell   = (int) toDouble(raw.get("sellTxCount"));

        v.winRatePct  = String.format("%.1f%%", wr * 100);
        v.winRateGood = wr >= 0.5;
        v.pnlPositive = pnl >= 0;
        v.pnlDisplay  = (pnl >= 0 ? "+$" : "-$") + fmtNum(Math.abs(pnl));
        v.top3Display = "$" + fmtNum(top3);
        v.txCountDisplay = buy + " / " + sell;
        v.avgBuyDisplay = "$" + fmtNum(avg);
        v.preferredMarketCap = str(raw.get("preferredMarketCap"));

        // 盈亏分布
        Object distRaw = raw.get("tokenCountByPnlPercent");
        if (distRaw instanceof Map<?,?> dist) {
            v.hasPnlDist   = true;
            v.over500      = (int) toDouble(dist.get("over500Percent"));
            v.zero500      = (int) toDouble(dist.get("zeroTo500Percent"));
            v.zeroMinus50  = (int) toDouble(dist.get("zeroToMinus50Percent"));
            v.overMinus50  = (int) toDouble(dist.get("overMinus50Percent"));
        }

        // Top3 代币
        Object topRaw = raw.get("topPnlTokenList");
        if (topRaw instanceof List<?> topList && !topList.isEmpty()) {
            String[] medals  = {"🥇", "🥈", "🥉"};
            String[] classes = {"sc-yellow", "sc-blue", "sc-cyan"};
            List<TopTokenVO> tokens = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(topList.size(), 3); i++) {
                Object item = topList.get(i);
                if (!(item instanceof Map<?,?> tm)) continue;
                TopTokenVO t = new TopTokenVO();
                double tPnl = toDouble(tm.get("tokenPnLUsd"));
                double tPct = toDouble(tm.get("tokenPnLPercent")) * 100;
                t.symbol     = str(tm.get("tokenSymbol") != null ? tm.get("tokenSymbol") : tm.get("symbol"));
                t.pnlDisplay = (tPnl >= 0 ? "+$" : "-$") + fmtNum(Math.abs(tPnl));
                t.pctDisplay = (tPct >= 0 ? "+" : "") + String.format("%.1f%%", tPct);
                t.positive   = tPnl >= 0;
                t.medal      = medals[i];
                t.cardClass  = classes[i];
                tokens.add(t);
            }
            v.topTokens = tokens;
        }

        return v;
    }

    private static double toDouble(Object v) {
        if (v == null) return 0;
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
    private static String fmtNum(double v) {
        if (v >= 1_000_000) return String.format("%.2fM", v / 1_000_000);
        if (v >= 1_000)     return String.format("%.0f", v).replaceAll("(\\d)(?=(\\d{3})+$)", "$1,");
        return String.format("%.2f", v);
    }
    private static String str(Object v) {
        return v == null ? "—" : String.valueOf(v);
    }
}
