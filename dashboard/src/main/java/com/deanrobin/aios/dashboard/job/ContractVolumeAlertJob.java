package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.PerpInstrument;
import com.deanrobin.aios.dashboard.model.PerpVolumeSnapshot;
import com.deanrobin.aios.dashboard.repository.PerpInstrumentRepository;
import com.deanrobin.aios.dashboard.repository.PerpVolumeSnapshotRepository;
import com.deanrobin.aios.dashboard.service.PerpApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合约成交量异动报警 Job。
 *
 * <p>功能一（每 30 min）：检测 1H 合约成交量异动
 *   - 对 BTC / ETH / BNB / SOL 拉取 Binance 永续合约最近 22 根 1H K 线
 *   - 若最新已收盘 K 线的成交额 > 20 期均值 × {@code VOLUME_RATIO_THRESHOLD}（默认 2.0×）
 *   - 且同品种 {@code SPIKE_COOLDOWN_H} 小时内未曾报警
 *   - → 从 Binance 拉取 OI（持仓量）、全球多空账户比、资金费率
 *   - → 写入 perp_volume_snapshot 表
 *   - → 推送飞书报警，含完整持仓快照
 *
 * <p>功能二（每 10 min）：24H / 48H 跟进报警
 *   - 扫描 perp_volume_snapshot 中已到期但未发跟进的记录
 *   - 拉取最新 OI / 多空比 / 费率与快照时对比
 *   - 推送变化摘要到飞书
 *
 * ⚠️ 不加 @Transactional
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class ContractVolumeAlertJob {

    // ── 监控品种（Binance symbol = symbol + "USDT"） ────────────────
    private static final List<String> SYMBOLS = List.of("BTC", "ETH", "BNB", "SOL");

    // ── 阈值 / 冷却 ─────────────────────────────────────────────────
    /** 成交量倍数阈值：当前 1H 成交额 > 20期均值 × 2.0 时触发 */
    private static final double VOLUME_RATIO_THRESHOLD = 2.0;
    /** 同品种报警冷却（小时） */
    private static final int    SPIKE_COOLDOWN_H       = 4;
    /** 跟进批次上限（每轮最多发几条，防止集中爆发时刷屏） */
    private static final int    FOLLOWUP_BATCH_LIMIT   = 5;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    private final PerpApiClient               perpApiClient;
    private final PerpVolumeSnapshotRepository snapshotRepo;
    private final PerpInstrumentRepository     instrumentRepo;
    private final WebClient.Builder            webClientBuilder;

    @Value("${perp.alert-url:}")
    private String alertUrl;

    /** 同品种上次报警时间（内存冷却，重启后重置属正常行为） */
    private final ConcurrentHashMap<String, Long> lastAlertMs = new ConcurrentHashMap<>();

    // ════════════════════════════════════════════════════════════════
    // 功能一：成交量异动检测（每 30 min，initialDelay 90s）
    // ════════════════════════════════════════════════════════════════

    @Scheduled(initialDelay = 90_000, fixedDelay = 1_800_000)
    public void checkVolumeAnomalies() {
        if (alertUrl == null || alertUrl.isBlank()) return;

        for (String symbol : SYMBOLS) {
            try {
                detectAndAlert(symbol);
            } catch (Exception e) {
                log.warn("⚠️ 成交量异动检测异常 symbol={}: {}", symbol, e.getMessage());
            }
        }
    }

    private void detectAndAlert(String symbol) {
        String binanceSymbol = symbol + "USDT";

        // 取 22 根 1H K 线（升序）：bars[0..19]=前20根 bars[20]=最新已收盘 bars[21]=当前进行中
        List<List<Object>> klines = perpApiClient.fetchBinanceKlines(binanceSymbol, "1h", 22);
        if (klines.size() < 22) {
            log.debug("⚠️ {} K 线数量不足 ({}/22)，跳过", symbol, klines.size());
            return;
        }

        // 最新已收盘 K 线（倒数第2，index=20）的成交额（USDT，index 7 = quoteAssetVolume）
        double currentVol = parseDouble(klines.get(20), 7);
        if (currentVol <= 0) return;

        // 前 20 根 K 线均量（index 0..19）
        double sumVol = 0;
        for (int i = 0; i < 20; i++) {
            sumVol += parseDouble(klines.get(i), 7);
        }
        double avgVol = sumVol / 20;
        if (avgVol <= 0) return;

        double ratio = currentVol / avgVol;
        if (ratio < VOLUME_RATIO_THRESHOLD) return;  // 未达阈值

        // 冷却判断
        long now = System.currentTimeMillis();
        long lastAlert = lastAlertMs.getOrDefault(symbol, 0L);
        if (now - lastAlert < (long) SPIKE_COOLDOWN_H * 3_600_000L) return;
        lastAlertMs.put(symbol, now);

        log.info("⚡ 合约成交量异动 symbol={} vol={} avg={} ratio=×{}",
                symbol, fmtVol(currentVol), fmtVol(avgVol), String.format("%.2f", ratio));

        // 收盘价（index 4）
        double closePrice = parseDouble(klines.get(20), 4);

        // 拉取持仓快照数据
        Double   oiUsdt    = perpApiClient.fetchBinanceOIUsdt(binanceSymbol);
        Map<String, Object> lsData     = perpApiClient.fetchBinanceLSRatio(binanceSymbol);
        Map<String, Object> takerData  = perpApiClient.fetchBinanceTakerRatio(binanceSymbol);

        // 资金费率（从 PerpInstrument 缓存）
        BigDecimal fundingRate = null;
        Optional<PerpInstrument> instOpt = instrumentRepo.findByExchangeAndSymbol("BINANCE", binanceSymbol);
        if (instOpt.isPresent()) {
            fundingRate = instOpt.get().getLatestFundingRate();
        }

        // 构建快照实体
        PerpVolumeSnapshot snap = new PerpVolumeSnapshot();
        snap.setSymbol(symbol);
        snap.setBar("1H");
        snap.setVolumeUsdt(bd(currentVol, 4));
        snap.setAvgVolumeUsdt(bd(avgVol, 4));
        snap.setVolumeRatio(bd(ratio, 4));
        snap.setClosePrice(closePrice > 0 ? bd(closePrice, 8) : null);
        snap.setOiUsdt(oiUsdt != null ? bd(oiUsdt, 4) : null);
        if (!lsData.isEmpty()) {
            snap.setLsRatio(parseBD(lsData.get("longShortRatio")));
            snap.setLongPct(parseBD(lsData.get("longAccount")));
            snap.setShortPct(parseBD(lsData.get("shortAccount")));
        }
        snap.setFundingRate(fundingRate);
        snap.setTakerBuyRatio(parseBD(takerData.get("buySellRatio")));
        snap.setSnappedAt(LocalDateTime.now(CST));
        snapshotRepo.save(snap);

        sendSpikeAlert(snap);
    }

    // ════════════════════════════════════════════════════════════════
    // 功能二：24H / 48H 跟进（每 10 min，initialDelay 120s）
    // ════════════════════════════════════════════════════════════════

    @Scheduled(initialDelay = 120_000, fixedDelay = 600_000)
    public void checkFollowups() {
        if (alertUrl == null || alertUrl.isBlank()) return;

        LocalDateTime now = LocalDateTime.now(CST);

        // 24H 跟进
        List<PerpVolumeSnapshot> due24h = snapshotRepo.findPending24hFollowup(now.minusHours(24));
        int sent24 = 0;
        for (PerpVolumeSnapshot snap : due24h) {
            if (sent24 >= FOLLOWUP_BATCH_LIMIT) break;
            try {
                sendFollowupAlert(snap, 24);
                snap.setFollowup24hDone(true);
                snapshotRepo.save(snap);
                sent24++;
            } catch (Exception e) {
                log.warn("⚠️ 24H 跟进发送失败 id={}: {}", snap.getId(), e.getMessage());
            }
        }

        // 48H 跟进
        List<PerpVolumeSnapshot> due48h = snapshotRepo.findPending48hFollowup(now.minusHours(48));
        int sent48 = 0;
        for (PerpVolumeSnapshot snap : due48h) {
            if (sent48 >= FOLLOWUP_BATCH_LIMIT) break;
            try {
                sendFollowupAlert(snap, 48);
                snap.setFollowup48hDone(true);
                snapshotRepo.save(snap);
                sent48++;
            } catch (Exception e) {
                log.warn("⚠️ 48H 跟进发送失败 id={}: {}", snap.getId(), e.getMessage());
            }
        }

        if (sent24 > 0 || sent48 > 0) {
            log.info("📤 成交量异动跟进 24H={} 48H={}", sent24, sent48);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 飞书消息构建
    // ════════════════════════════════════════════════════════════════

    private void sendSpikeAlert(PerpVolumeSnapshot snap) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚡ 合约成交量异动\n");
        sb.append(String.format("品种: %s  |  1H 合约（Binance）\n", snap.getSymbol()));
        sb.append(String.format("成交额: %s（均量×%.1f）\n",
                fmtVol(snap.getVolumeUsdt().doubleValue()),
                snap.getVolumeRatio().doubleValue()));
        if (snap.getClosePrice() != null) {
            sb.append(String.format("当前价格: %s USDT\n", fmtPrice(snap.getClosePrice().doubleValue())));
        }
        sb.append("\n📊 持仓快照（Binance）\n");
        if (snap.getOiUsdt() != null) {
            sb.append(String.format("持仓量 OI: %s USDT\n", fmtVol(snap.getOiUsdt().doubleValue())));
        } else {
            sb.append("持仓量 OI: --\n");
        }
        appendLSLine(sb, snap.getLsRatio(), snap.getLongPct(), snap.getShortPct());
        if (snap.getFundingRate() != null) {
            double fr = snap.getFundingRate().doubleValue() * 100;
            sb.append(String.format("资金费率: %+.4f%%\n", fr));
        } else {
            sb.append("资金费率: --\n");
        }
        appendTakerLine(sb, snap.getTakerBuyRatio());
        sb.append(String.format("\n⏰ %s 快照 | 将在 24H / 48H 后跟进",
                snap.getSnappedAt().format(FMT)));

        sendFeishu(sb.toString());
        log.info("⚡ 成交量异动报警已发送 | {} ratio=×{}", snap.getSymbol(),
                String.format("%.2f", snap.getVolumeRatio().doubleValue()));
    }

    private void sendFollowupAlert(PerpVolumeSnapshot snap, int hours) {
        String binanceSymbol = snap.getSymbol() + "USDT";

        // 拉取当前数据
        Double   currentOI      = perpApiClient.fetchBinanceOIUsdt(binanceSymbol);
        Map<String, Object> currentLS     = perpApiClient.fetchBinanceLSRatio(binanceSymbol);
        Map<String, Object> currentTaker  = perpApiClient.fetchBinanceTakerRatio(binanceSymbol);
        BigDecimal currentFR = null;
        Optional<PerpInstrument> instOpt = instrumentRepo.findByExchangeAndSymbol("BINANCE", binanceSymbol);
        if (instOpt.isPresent()) currentFR = instOpt.get().getLatestFundingRate();

        // 当前价格（从最新1H K线取收盘价，取最后一根，即正在进行的或刚收盘的K线）
        List<List<Object>> klines = perpApiClient.fetchBinanceKlines(binanceSymbol, "1h", 2);
        double currentPrice = klines.isEmpty() ? 0 : parseDouble(klines.get(klines.size() - 1), 4);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 [%dH 跟进] %s 成交量异动\n", hours, snap.getSymbol()));
        sb.append(String.format("快照时间: %s\n", snap.getSnappedAt().format(FMT)));
        sb.append(String.format("触发成交额: %s（均量×%.1f）\n\n",
                fmtVol(snap.getVolumeUsdt().doubleValue()),
                snap.getVolumeRatio().doubleValue()));

        // 价格变化
        if (snap.getClosePrice() != null && currentPrice > 0) {
            double snapPrice = snap.getClosePrice().doubleValue();
            double pct = (currentPrice - snapPrice) / snapPrice * 100;
            sb.append(String.format("价格: %s → %s (%+.2f%%)\n",
                    fmtPrice(snapPrice), fmtPrice(currentPrice), pct));
        }

        // OI 变化
        if (snap.getOiUsdt() != null && currentOI != null) {
            double snapOI = snap.getOiUsdt().doubleValue();
            double pct = (currentOI - snapOI) / snapOI * 100;
            sb.append(String.format("持仓量 OI: %s → %s (%+.2f%%)\n",
                    fmtVol(snapOI), fmtVol(currentOI), pct));
        } else if (currentOI != null) {
            sb.append(String.format("当前 OI: %s USDT\n", fmtVol(currentOI)));
        }

        // 当前多空比
        appendLSLine(sb, parseBD(currentLS.get("longShortRatio")),
                parseBD(currentLS.get("longAccount")), parseBD(currentLS.get("shortAccount")));

        // 当前费率
        if (currentFR != null) {
            double fr = currentFR.doubleValue() * 100;
            sb.append(String.format("资金费率: %+.4f%%\n", fr));
        } else {
            sb.append("资金费率: --\n");
        }

        // 当前主动买/卖比
        appendTakerLine(sb, parseBD(currentTaker.get("buySellRatio")));

        sendFeishu(sb.toString());
    }

    private void appendLSLine(StringBuilder sb, BigDecimal lsRatio,
                              BigDecimal longPct, BigDecimal shortPct) {
        if (lsRatio != null && longPct != null && shortPct != null) {
            sb.append(String.format("多空账户比: %.2f（多%.1f%% / 空%.1f%%）\n",
                    lsRatio.doubleValue(),
                    longPct.doubleValue() * 100,
                    shortPct.doubleValue() * 100));
        } else {
            sb.append("多空账户比: --\n");
        }
    }

    /**
     * 追加主动买/卖量比行。
     * buySellRatio = buyVol / sellVol，由此推算买入占比 = ratio / (1 + ratio)。
     */
    private void appendTakerLine(StringBuilder sb, BigDecimal buySellRatio) {
        if (buySellRatio != null) {
            double r = buySellRatio.doubleValue();
            double buyPct  = r / (1.0 + r) * 100;
            double sellPct = 100.0 - buyPct;
            sb.append(String.format("主动买/卖比: %.2f（买%.1f%% / 卖%.1f%%）\n",
                    r, buyPct, sellPct));
        } else {
            sb.append("主动买/卖比: --\n");
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 工具方法
    // ════════════════════════════════════════════════════════════════

    /** 从 Binance K 线行（List<Object>）按索引取 double */
    private static double parseDouble(List<Object> row, int idx) {
        if (row == null || idx >= row.size() || row.get(idx) == null) return 0;
        try { return Double.parseDouble(String.valueOf(row.get(idx))); } catch (Exception e) { return 0; }
    }

    private static BigDecimal parseBD(Object obj) {
        if (obj == null) return null;
        try { return new BigDecimal(String.valueOf(obj)); } catch (Exception e) { return null; }
    }

    private static BigDecimal bd(double val, int scale) {
        return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP);
    }

    /** 格式化成交量 / OI：自动换算为亿/万/USDT */
    private static String fmtVol(double usdt) {
        if (usdt >= 1e8)  return String.format("%.2f亿", usdt / 1e8);
        if (usdt >= 1e4)  return String.format("%.0f万", usdt / 1e4);
        return String.format("%.0f", usdt);
    }

    /** 格式化价格 */
    private static String fmtPrice(double price) {
        if (price >= 1000) return String.format("%,.0f", price);
        if (price >= 1)    return String.format("%.4f", price);
        return String.format("%.8f", price);
    }

    private void sendFeishu(String text) {
        if (alertUrl == null || alertUrl.isBlank()) return;
        try {
            Map<String, Object> body = Map.of("msg_type", "text", "content", Map.of("text", text));
            webClientBuilder.build().post().uri(alertUrl)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve().bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                    .subscribe();
        } catch (Exception e) {
            log.warn("⚠️ 飞书发送失败: {}", e.getMessage());
        }
    }
}
