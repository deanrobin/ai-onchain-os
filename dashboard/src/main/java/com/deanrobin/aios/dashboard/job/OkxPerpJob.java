package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.PerpFundingRate;
import com.deanrobin.aios.dashboard.model.PerpInstrument;
import com.deanrobin.aios.dashboard.repository.PerpFundingRateRepository;
import com.deanrobin.aios.dashboard.repository.PerpInstrumentRepository;
import com.deanrobin.aios.dashboard.service.PerpApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OKX 永续合约独立 Job（品种同步 + 资金费率）。
 * 与 Binance / Hyperliquid Job 完全独立，互不影响。
 *
 * - syncInstruments()    每 5 min：同步 OKX USDT-M 品种，新品种飞书报警
 * - fetchAllRates()      每 10 min：逐个拉资金费率，间隔 1.2s
 * - fetchWatchedRates()  每 1 min：仅拉 is_watched=1 品种
 *
 * ⚠️ 不加 @Transactional
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class OkxPerpJob {

    private static final String   EXCHANGE    = "OKX";
    private static final long     DELAY_MS    = 500L;  // OKX 限速 20req/2s，500ms 安全裕量充足，原 1200ms 导致 300 种 × 1.2s = 6min 阻塞

    private final PerpApiClient             perpApiClient;
    private final PerpInstrumentRepository  instrumentRepo;
    private final PerpFundingRateRepository fundingRateRepo;
    private final WebClient.Builder         webClientBuilder;

    @Value("${perp.alert-url:}")
    private String alertUrl;

    /** 全量资金费率任务防并发重入 */
    private final AtomicBoolean rateAllRunning = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        log.info("✅ OkxPerpJob 初始化完成 | DELAY_MS={} | JVM线程数={}", DELAY_MS, tmx.getThreadCount());
    }

    // ═══ 品种同步（每 5 min，initialDelay 10s）═══════════════════════
    @Scheduled(initialDelay = 10_000, fixedDelay = 300_000)
    public void syncInstruments() {
        List<Map<String, Object>> instruments = perpApiClient.fetchOkxInstruments();
        log.info("🔍 OKX syncInstruments 触发 | 拉取品种数={}", instruments.size());
        if (instruments.isEmpty()) return;

        int newCount = 0;
        int usdtCount = 0;
        List<String> newSymbols = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // 打印前 3 个品种的原始字段，便于确认 quoteCcy / settleCcy 实际值
        instruments.stream().limit(3).forEach(item ->
            log.info("🔬 OKX instrument sample | instId={} baseCcy={} quoteCcy={} settleCcy={}",
                item.get("instId"), item.get("baseCcy"), item.get("quoteCcy"), item.get("settleCcy"))
        );

        for (Map<String, Object> item : instruments) {
            String symbol   = String.valueOf(item.getOrDefault("instId",    ""));
            if (symbol.isBlank()) continue;
            String base     = String.valueOf(item.getOrDefault("baseCcy",   ""));
            String quote    = String.valueOf(item.getOrDefault("quoteCcy",  ""));
            String settle   = String.valueOf(item.getOrDefault("settleCcy", ""));

            // 只保留 USDT 结算：quoteCcy=USDT 或 settleCcy=USDT（双重兜底）
            boolean isUsdt  = "USDT".equalsIgnoreCase(quote) || "USDT".equalsIgnoreCase(settle);
            if (!isUsdt) {
                instrumentRepo.findByExchangeAndSymbol(EXCHANGE, symbol).ifPresent(pi -> {
                    if (Boolean.TRUE.equals(pi.getIsActive())) {
                        pi.setIsActive(false);
                        instrumentRepo.save(pi);
                    }
                });
                continue;
            }
            usdtCount++;

            var opt = instrumentRepo.findByExchangeAndSymbol(EXCHANGE, symbol);
            if (opt.isEmpty()) {
                PerpInstrument pi = new PerpInstrument();
                pi.setExchange(EXCHANGE);
                pi.setSymbol(symbol);
                pi.setBaseCurrency(base);
                pi.setQuoteCurrency(quote.isBlank() ? settle : quote);
                pi.setFirstSeenAt(now);
                pi.setLastSeenAt(now);
                instrumentRepo.save(pi);
                newCount++;
                newSymbols.add(symbol);
            } else {
                PerpInstrument pi = opt.get();
                boolean changed = false;
                if (!Boolean.TRUE.equals(pi.getIsActive())) {
                    pi.setIsActive(true);
                    pi.setLastSeenAt(now);
                    changed = true;
                }
                // 兜底修复历史记录 quoteCurrency 为空（导致 Top10 查询被过滤掉）
                if (pi.getQuoteCurrency() == null || pi.getQuoteCurrency().isBlank()) {
                    pi.setQuoteCurrency(quote.isBlank() ? settle : quote);
                    changed = true;
                }
                if (changed) instrumentRepo.save(pi);
            }
        }
        log.info("🔍 OKX syncInstruments 完成 | USDT品种={} 新增={}", usdtCount, newCount);
        if (newCount > 0) {
            triggerAlert(newSymbols.size(), String.join(",", newSymbols.subList(0, Math.min(3, newSymbols.size()))));
        }
    }

    // ═══ 全量资金费率（每 10 min，initialDelay 60s）══════════════════
    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
    public void fetchAllRates() {
        if (!rateAllRunning.compareAndSet(false, true)) {
            log.warn("⚠️ OKX 全量费率任务上次未完成，跳过本次");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            List<PerpInstrument> list = instrumentRepo.findByExchangeAndIsActiveTrue(EXCHANGE);
            log.info("🔄 OKX 资金费率全量开始 | 品种数={} | thread={}", list.size(), Thread.currentThread().getName());
            LocalDateTime now = LocalDateTime.now();
            int saved = 0;
            for (PerpInstrument inst : list) {
                try {
                    if (fetchAndSave(inst, now)) saved++;
                    Thread.sleep(DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("⚠️ OKX 费率获取失败 {}: {}", inst.getSymbol(), e.getMessage());
                }
            }
            log.info("📊 OKX 资金费率全量完成 | 更新={} 条 | 耗时={}s", saved, (System.currentTimeMillis() - start) / 1000);
        } finally {
            rateAllRunning.set(false);
        }
    }

    // ═══ 关注品种资金费率（每 1 min，initialDelay 30s）═══════════════
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void fetchWatchedRates() {
        List<PerpInstrument> watched = instrumentRepo.findByIsWatchedTrue().stream()
                .filter(p -> EXCHANGE.equals(p.getExchange())).toList();
        if (watched.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        int saved = 0;
        for (PerpInstrument inst : watched) {
            try {
                if (fetchAndSave(inst, now)) saved++;
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("⚠️ OKX 关注品种费率失败 {}: {}", inst.getSymbol(), e.getMessage());
            }
        }
        if (saved > 0) log.debug("⭐ OKX 关注品种费率更新 {} 条", saved);
    }

    // ─── 内部：单品种拉取并保存 ──────────────────────────────────────
    private boolean fetchAndSave(PerpInstrument inst, LocalDateTime now) {
        Map<String, Object> data = perpApiClient.fetchOkxFundingRate(inst.getSymbol());
        if (data.isEmpty()) return false;
        BigDecimal rate    = parseBD(data.get("fundingRate"));
        LocalDateTime next = PerpApiClient.msToLdt(data.get("fundingTime"));

        PerpFundingRate snap = new PerpFundingRate();
        snap.setExchange(EXCHANGE);
        snap.setSymbol(inst.getSymbol());
        snap.setFundingRate(rate);
        snap.setNextFundingTime(next);
        snap.setFetchedAt(now);
        fundingRateRepo.save(snap);

        inst.setLatestFundingRate(rate);
        inst.setLatestFundingUpdatedAt(now);
        instrumentRepo.save(inst);
        return true;
    }

    // ─── 飞书报警 ────────────────────────────────────────────────────
    private void triggerAlert(int count, String sample) {
        if (alertUrl == null || alertUrl.isBlank()) return;
        try {
            String text = String.format("🆕 OKX 新增永续合约\n数量：%d 个\n示例：%s", count, sample);
            Map<String, Object> body = Map.of("msg_type", "text", "content", Map.of("text", text));
            webClientBuilder.build().post().uri(alertUrl)
                    .header("Content-Type", "application/json").bodyValue(body)
                    .retrieve().bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(5))
                    .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                    .subscribe();
        } catch (Exception e) {
            log.warn("⚠️ OKX 飞书报警失败: {}", e.getMessage());
        }
    }

    private BigDecimal parseBD(Object obj) {
        if (obj == null) return null;
        try { return new BigDecimal(String.valueOf(obj)); } catch (Exception e) { return null; }
    }
}
