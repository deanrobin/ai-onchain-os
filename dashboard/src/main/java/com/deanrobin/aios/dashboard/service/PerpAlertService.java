package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.model.PerpFundingRate;
import com.deanrobin.aios.dashboard.model.PerpInstrument;
import com.deanrobin.aios.dashboard.repository.PerpFundingRateRepository;
import com.deanrobin.aios.dashboard.repository.PerpInstrumentRepository;
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

    private final PerpService               perpService;
    private final PerpInstrumentRepository  instrumentRepo;
    private final PerpFundingRateRepository fundingRateRepo;
    private final WebClient.Builder         webClientBuilder;

    @Value("${perp.alert-url:}")
    private String alertUrl;

    /** spike 报警冷却：key = "EXCHANGE:SYMBOL"，value = 上次报警时间戳 */
    private final ConcurrentHashMap<String, Long> spikeCooldown = new ConcurrentHashMap<>();

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

    // ─── 构建并发送 Top10 报告 ────────────────────────────────────────
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
            List<PerpInstrument> high = perpService.getTop10High(exch);
            List<PerpInstrument> low  = perpService.getTop10Low(exch);
            if (high.isEmpty() && low.isEmpty()) continue;

            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append(icon).append(" ").append(exch).append("\n");

            sb.append("▲ Top10 最高\n");
            appendRates(sb, high);
            sb.append("▼ Top10 最低\n");
            appendRates(sb, low);
        }

        sendFeishu(sb.toString());
        log.info("📤 Perps 飞书汇报已发送");
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

        // 与当前 latest_funding_rate 对比
        List<PerpInstrument> current = instrumentRepo.findByExchangeAndIsActiveTrue(exchange);
        for (PerpInstrument inst : current) {
            if (inst.getLatestFundingRate() == null) continue;
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
