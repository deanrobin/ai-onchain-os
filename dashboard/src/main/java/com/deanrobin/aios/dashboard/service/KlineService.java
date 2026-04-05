package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.model.KlineBar;
import com.deanrobin.aios.dashboard.repository.KlineBarRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * K 线均线计算 + 策略分析服务。
 * <p>
 * 均线：
 *   MA20 / MA120  — 简单移动平均
 *   EMA144 / EMA169 — 指数移动平均（乘数 k = 2/(n+1)）
 * <p>
 * 策略打分（满分 4 分）：
 *   +1  价格 > MA20（站上短期均线）
 *   +1  MA20  > MA120（短期均线在长期均线上方，上升趋势）
 *   +1  EMA144 > EMA169（EMA 多头排列）
 *   +1  最新 K 线成交量 > 20 期均量 × 1.2（成交量放大）
 * <p>
 * 信号：≥3 = 看多 BULL，≤1 = 看空 BEAR，其余 = 中性 NEUTRAL
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class KlineService {

    // 需要至少加载多少根 K 线来计算指标
    public static final int LOAD_LIMIT = 220;

    private final KlineBarRepository klineRepo;

    // ──────────────────────────────────────────────────────────────
    // 公开 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 返回指定 symbol + bar 的均线指标 + 策略结果。
     * 结果 Map 键：ma20, ma120, ema144, ema169, volume, avgVolume20,
     *              signal, signalScore, reasons, validHours,
     *              bars（最近 N 根 K 线，用于前端渲染）
     */
    public Map<String, Object> analyze(String symbol, String bar) {
        List<KlineBar> raw = klineRepo.findLatestBars(symbol, bar,
                PageRequest.of(0, LOAD_LIMIT));

        // 倒序 → 时间正序（旧 → 新）
        List<KlineBar> bars = new ArrayList<>(raw);
        Collections.reverse(bars);

        int n = bars.size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("bar", bar);
        result.put("dataCount", n);

        if (n < 20) {
            result.put("signal", "WAIT");
            result.put("signalScore", 0);
            result.put("reasons", List.of("数据不足（需≥20根）"));
            result.put("validHours", 0);
            return result;
        }

        List<Double> closes  = bars.stream().map(k -> k.getClosePrice().doubleValue()).collect(Collectors.toList());
        List<Double> volumes = bars.stream().map(k -> k.getVolumeUsdt() != null
                ? k.getVolumeUsdt().doubleValue() : k.getVolume().doubleValue()).collect(Collectors.toList());

        // ── 均线计算 ──────────────────────────────────────────────
        Double ma20   = sma(closes, 20);
        Double ma120  = n >= 120 ? sma(closes, 120) : null;
        Double ema144 = n >= 144 ? ema(closes, 144) : null;
        Double ema169 = n >= 169 ? ema(closes, 169) : null;

        double currentPrice = closes.get(n - 1);

        // ── 成交量 ──────────────────────────────────────────────
        double latestVol   = volumes.get(n - 1);
        double avgVol20    = volumes.subList(Math.max(0, n - 20), n)
                .stream().mapToDouble(Double::doubleValue).average().orElse(0);

        // ── 策略打分 ──────────────────────────────────────────────
        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (ma20 != null && currentPrice > ma20) {
            score++;
            reasons.add("价格 > MA20（站上短期均线）");
        } else if (ma20 != null) {
            reasons.add("价格 < MA20（跌破短期均线）");
        }

        if (ma20 != null && ma120 != null) {
            if (ma20 > ma120) {
                score++;
                reasons.add("MA20 > MA120（上升趋势）");
            } else {
                reasons.add("MA20 < MA120（下降趋势）");
            }
        }

        if (ema144 != null && ema169 != null) {
            if (ema144 > ema169) {
                score++;
                reasons.add("EMA144 > EMA169（EMA 多头排列）");
            } else {
                reasons.add("EMA144 < EMA169（EMA 空头排列）");
            }
        }

        if (avgVol20 > 0 && latestVol > avgVol20 * 1.2) {
            score++;
            reasons.add(String.format("成交量放大（×%.1f 均量）", latestVol / avgVol20));
        } else if (avgVol20 > 0) {
            reasons.add(String.format("成交量偏低（×%.1f 均量）", latestVol / avgVol20));
        }

        String signal;
        if (score >= 3)      signal = "BULL";
        else if (score <= 1) signal = "BEAR";
        else                 signal = "NEUTRAL";

        // ── 填充结果 ──────────────────────────────────────────────
        result.put("currentPrice", round(currentPrice, 4));
        result.put("ma20",   ma20   != null ? round(ma20,   4) : null);
        result.put("ma120",  ma120  != null ? round(ma120,  4) : null);
        result.put("ema144", ema144 != null ? round(ema144, 4) : null);
        result.put("ema169", ema169 != null ? round(ema169, 4) : null);
        result.put("volume",      round(latestVol, 2));
        result.put("avgVolume20", round(avgVol20,  2));
        result.put("signal",      signal);
        result.put("signalScore", score);
        result.put("reasons",     reasons);
        result.put("validHours",  validHours(bar));
        result.put("openTime",    bars.get(n - 1).getOpenTime());

        return result;
    }

    // ──────────────────────────────────────────────────────────────
    // 私有工具方法
    // ──────────────────────────────────────────────────────────────

    /** 简单移动平均（取最后 period 个数） */
    private Double sma(List<Double> series, int period) {
        int n = series.size();
        if (n < period) return null;
        return series.subList(n - period, n).stream()
                .mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /**
     * 指数移动平均（EMA）。
     * 从第一个数据点开始迭代，数据越多结果越准（"热身"效应随数据增多而消退）。
     * k = 2 / (period + 1)
     */
    private Double ema(List<Double> series, int period) {
        if (series.isEmpty()) return null;
        double k = 2.0 / (period + 1);
        double emaVal = series.get(0);
        for (int i = 1; i < series.size(); i++) {
            emaVal = series.get(i) * k + emaVal * (1 - k);
        }
        return emaVal;
    }

    /** 不同 bar 对应的策略信号有效期（小时） */
    public static int validHours(String bar) {
        return switch (bar) {
            case "15m" -> 4;
            case "1H"  -> 24;
            case "4H"  -> 72;   // 3 天
            case "1D"  -> 168;  // 7 天
            case "1W"  -> 720;  // 30 天
            default    -> 24;
        };
    }

    private static double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
