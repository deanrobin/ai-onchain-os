package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.model.PerpFundingRate;
import com.deanrobin.aios.dashboard.model.PerpInstrument;
import com.deanrobin.aios.dashboard.repository.PerpFundingRateRepository;
import com.deanrobin.aios.dashboard.repository.PerpInstrumentRepository;
import com.deanrobin.aios.dashboard.repository.TickerAlertBlacklistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Perps 飞书报警服务。
 *
 * 功能一：定时汇报（7:55 / 15:55 / 23:55 北京时间）
 *         + 手动触发接口（5 分钟限流）
 *
 * 功能二：费率突变检测
 *         每次全量/关注品种费率更新后调用，对比 15min 前的值，
 *         绝对值变化 > 0.5% 且远离 0 的方向 → 飞书报警。
 *         每个品种每小时最多报一次（JVM 内存缓存）。
 *
 * ⚠️ 不加 @Transactional
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class PerpAlertService {

    private static final double SPIKE_THRESHOLD    = 0.005;          // 0.5%
    private static final long   SPIKE_COOLDOWN_MS  = 3_600_000L;     // 1 小时
    private static final long   REPORT_COOLDOWN_MS = 300_000L;       // 5 分钟

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    private final PerpService                      perpService;
    private final PerpInstrumentRepository         instrumentRepo;
    private final PerpFundingRateRepository        fundingRateRepo;
    private final TickerAlertBlacklistRepository   blacklistRepo;
    private final WebClient.Builder                webClientBuilder;

    @Value("${perp.alert-url:}")
    private String alertUrl;

    /** spike 报警冷却：key = "EXCHANGE:SYMBOL"，value = 上次报警时间戳 */
    private final ConcurrentHashMap<String, Long> spikeCooldown = new ConcurrentHashMap<>();

    /** 成交量报警冷却：key = symbol，value = 上次报警时间戳（1H 最多一次） */
    private final ConcurrentHashMap<String, Long> volumeCooldown = new ConcurrentHashMap<>();

    /** 黑名单缓存，每 5 分钟从 DB 刷新一次 */
    private volatile Set<String> blacklistCache = Set.of();

    @Scheduled(initialDelay = 0, fixedDelay = 300_000)
    public void refreshBlacklist() {
        try {
            blacklistCache = blacklistRepo.findAllSymbols();
            log.debug("🔕 成交量报警黑名单已刷新，共 {} 个", blacklistCache.size());
        } catch (Exception e) {
            log.warn("⚠️ 黑名单刷新失败: {}", e.getMessage());
        }
    }

    /** 手动汇报冷却 */
    private final AtomicLong lastManualReport = new AtomicLong(0);

    // ════════════════════════════════════════════════════════════════
    // 功能一：定时 / 手动报告
    // ════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 55 7,15,23 * * *", zone = "Asia/Shanghai")
    public void scheduledReport() {
        log.info("📤 Perps 定时飞书汇报触发");
        sendReport();
    }

    /**
     * 手动触发汇报。
     * @return true = 已发送；false = 5 分钟内已触发过，限流拒绝
     */
    public boolean triggerManualReport() {
        long now = System.currentTimeMillis();
        if (now - lastManualReport.get() < REPORT_COOLDOWN_MS) {
            return false;
        }
        lastManualReport.set(now);
        sendReport();
        return true;
    }

    // ─── 构建并发送 Top5 飞书报告（页面仍展示 Top10）────────────────
    private void sendReport() {
        if (alertUrl == null || alertUrl.isBlank()) {
            log.warn("⚠️ perp.alert-url 未配置，跳过飞书汇报");
            return;
        }
        String time = LocalDateTime.now(CST).format(FMT);
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Perps 资金费率汇报 | ").append(time).append("\n");

        String[][] exchanges = {
            {"OKX",         "🟡"},
            {"BINANCE",     "🟠"},
            {"HYPERLIQUID", "🔵"},
        };

        for (String[] ex : exchanges) {
            String exch = ex[0], icon = ex[1];
            List<PerpInstrument> high = top5(perpService.getTop10High(exch));
            List<PerpInstrument> low  = top5(perpService.getTop10Low(exch));
            if (high.isEmpty() && low.isEmpty()) continue;

            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append(icon).append(" ").append(exch).append("\n");

            sb.append("▲ Top5 最高\n");
            appendRates(sb, high);
            sb.append("▼ Top5 最低\n");
            appendRates(sb, low);
        }

        sendFeishu(sb.toString());
        log.info("📤 Perps 飞书汇报已发送");
    }

    private static List<PerpInstrument> top5(List<PerpInstrument> list) {
        return list.size() <= 5 ? list : list.subList(0, 5);
    }

    private void appendRates(StringBuilder sb, List<PerpInstrument> list) {
        for (int i = 0; i < list.size(); i++) {
            PerpInstrument p = list.get(i);
            if (p.getLatestFundingRate() == null) continue;
            double pct = p.getLatestFundingRate().doubleValue() * 100;
            // 展示 baseCurrency（有值用它，否则截取 symbol 前缀）
            String label = (p.getBaseCurrency() != null && !p.getBaseCurrency().isBlank())
                    ? p.getBaseCurrency()
                    : p.getSymbol().split("[-/]")[0];
            sb.append(String.format("  %2d. %-8s %+.4f%%\n", i + 1, label, pct));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 功能二：费率突变检测
    // ════════════════════════════════════════════════════════════════

    /**
     * 全量 / 关注品种费率更新后调用，检测突变并报警。
     * @param exchange 交易所名称（OKX / BINANCE / HYPERLIQUID）
     */
    public void checkSpikes(String exchange) {
        if (alertUrl == null || alertUrl.isBlank()) return;

        LocalDateTime now  = LocalDateTime.now();
        LocalDateTime from = now.minusMinutes(25);
        LocalDateTime to   = now.minusMinutes(5);

        // 一次查出约 15 分钟前窗口内所有费率记录
        List<PerpFundingRate> historical = fundingRateRepo
                .findByExchangeAndFetchedAtBetween(exchange, from, to);
        if (historical.isEmpty()) return;

        // 每个 symbol 取最新的一条（同一批次 fetchedAt 相同，取任意即可）
        Map<String, BigDecimal> prev15 = historical.stream().collect(
                Collectors.toMap(
                        PerpFundingRate::getSymbol,
                        PerpFundingRate::getFundingRate,
                        (a, b) -> b   // 保留后一条
                ));

        // 与当前 latest_funding_rate 对比（仅 active 品种，且跳过黑名单）
        List<PerpInstrument> current = instrumentRepo.findByExchangeAndIsActiveTrue(exchange);
        for (PerpInstrument inst : current) {
            if (inst.getLatestFundingRate() == null) continue;
            if (blacklistCache.contains(inst.getSymbol())) continue;   // 黑名单跳过
            BigDecimal prevBD = prev15.get(inst.getSymbol());
            if (prevBD == null) continue;

            double curr    = inst.getLatestFundingRate().doubleValue();
            double prev    = prevBD.doubleValue();
            double absCurr = Math.abs(curr);
            double absPrev = Math.abs(prev);
            double absDiff = Math.abs(curr - prev);

            // 变化 > 0.5% 且 绝对值在扩大（远离 0）
            if (absDiff >= SPIKE_THRESHOLD && absCurr > absPrev) {
                String key = exchange + ":" + inst.getSymbol();
                long lastAlert = spikeCooldown.getOrDefault(key, 0L);
                if (System.currentTimeMillis() - lastAlert >= SPIKE_COOLDOWN_MS) {
                    spikeCooldown.put(key, System.currentTimeMillis());
                    sendSpikeAlert(exchange, inst.getSymbol(), prev, curr);
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 功能三：OI 突破 5000万 告警
    // ════════════════════════════════════════════════════════════════

    /**
     * 持仓量首次突破 5000万 USD 时调用，发送飞书通知。
     * 48h 内同品种不重复（由 PerpOiJob 的 checkAndAlert 保证）。
     */
    public void sendOiBreakAlert(String exchange, String symbol, String baseCurrency, java.math.BigDecimal oiUsd) {
        if (alertUrl == null || alertUrl.isBlank()) return;
        String label = (baseCurrency != null && !baseCurrency.isBlank())
                ? baseCurrency : symbol.split("[-/]")[0];
        String oiFmt = formatUsd(oiUsd);
        String text = String.format(
                "🚨 合约持仓量突破 5000万 USD\n交易所：%s\n合约：%s（%s）\n当前持仓量：%s\n\n" +
                "已进入 48h 特别关注模式\n每 5 分钟快照价格 / 涨跌 / 持仓量",
                exchange, symbol, label, oiFmt);
        sendFeishu(text);
        log.info("🚨 OI突破告警飞书已发 | {}:{} | OI={}", exchange, symbol, oiFmt);
    }

    private String formatUsd(java.math.BigDecimal v) {
        if (v == null) return "—";
        double d = v.doubleValue();
        if (d >= 1e9)  return String.format("$%.2fB", d / 1e9);
        if (d >= 1e6)  return String.format("$%.1fM", d / 1e6);
        if (d >= 1e3)  return String.format("$%.1fK", d / 1e3);
        return String.format("$%.0f", d);
    }

    private void sendSpikeAlert(String exchange, String symbol, double prev, double curr) {
        double diff   = curr - prev;
        String arrow  = diff > 0 ? "↑" : "↓";
        String text = String.format(
                "⚡ 资金费率异动\n交易所: %s\n合约: %s\n15分钟前: %+.4f%%\n当前: %+.4f%%\n变动: %+.4f%% %s",
                exchange, symbol, prev * 100, curr * 100, diff * 100, arrow);
        sendFeishu(text);
        log.info("⚡ 费率异动报警 | {}:{} | 15min前={:+.4f}% → 当前={:+.4f}%",
                exchange, symbol, prev * 100, curr * 100);
    }

    // ════════════════════════════════════════════════════════════════
    // 功能三：合约成交量报警（> 5000w USDT）
    // ════════════════════════════════════════════════════════════════

    /**
     * Binance ticker job 每分钟调用，成交额超阈值时发飞书报警。
     * 每个品种每小时最多报一次。
     */
    public void checkVolumeAlert(String symbol, java.math.BigDecimal quoteVolume) {
        if (alertUrl == null || alertUrl.isBlank()) return;
        // 黑名单过滤
        if (blacklistCache.contains(symbol)) return;
        // 1H 冷却
        String key = "VOL:" + symbol;
        long last = volumeCooldown.getOrDefault(key, 0L);
        if (System.currentTimeMillis() - last < SPIKE_COOLDOWN_MS) return;
        volumeCooldown.put(key, System.currentTimeMillis());
        double vol = quoteVolume.doubleValue();
        String text = String.format(
                "🔥 合约成交量异动\n合约: %s\n24h成交额: %.0f USDT (%.1f亿)",
                symbol, vol, vol / 1_0000_0000.0);
        sendFeishu(text);
        log.info("🔥 成交量报警 | {} | 24h成交额={} USDT", symbol, String.format("%.0f", vol));
    }

    // ─── 飞书发送 ────────────────────────────────────────────────────
    private void sendFeishu(String text) {
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
