package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.KlineBar;
import com.deanrobin.aios.dashboard.repository.KlineBarRepository;
import com.deanrobin.aios.dashboard.service.KlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * K 线数据抓取任务。
 *
 * <ul>
 *   <li>每 30s 拉取最新 5 根 K 线（覆盖进行中的 K 线）</li>
 *   <li>首次启动如本地数据 < 180 根，自动补录 300 根历史</li>
 *   <li>00:20 按各 bar 保留策略清理过期数据：
 *       15m=7天 | 1H=30天 | 4H=180天 | 1D=730天 | 1W=2000天</li>
 * </ul>
 *
 * OKX 公开端点，无需签名：
 * GET https://www.okx.com/api/v5/market/candles?instId=BTC-USDT&bar=15m&limit=5
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class KlineFetchJob {

    private static final List<String> SYMBOLS = List.of("BTC", "ETH", "BNB", "SOL", "XAUT");
    private static final List<String> BARS    = List.of("15m", "1H", "4H", "1D", "1W");

    private static final String BASE_URL = "https://www.okx.com";
    private static final int    HISTORY_THRESHOLD = 180; // 低于此数量触发批量补录
    private static final int    BATCH_LIMIT       = 300; // OKX 单次最大返回条数

    private final KlineBarRepository klineRepo;
    private final WebClient.Builder  webClientBuilder;

    // ──────────────────────────────────────────────────────────────
    // 启动时补录历史（延迟 15s 避免与价格任务争抢）
    // ──────────────────────────────────────────────────────────────
    @Scheduled(initialDelay = 15_000, fixedDelay = Long.MAX_VALUE)
    public void backfillOnStartup() {
        log.info("📦 K 线历史补录开始...");
        WebClient client = webClientBuilder.baseUrl(BASE_URL).build();
        int saved = 0;
        for (String symbol : SYMBOLS) {
            for (String bar : BARS) {
                long count = klineRepo.countBySymbolAndBar(symbol, bar);
                if (count < HISTORY_THRESHOLD) {
                    saved += fetchAndSave(client, symbol, bar, BATCH_LIMIT);
                    sleepMs(200); // OKX 公开接口限速约 20 次/秒
                }
            }
        }
        log.info("📦 K 线历史补录完成，共 upsert {} 条", saved);
    }

    // ──────────────────────────────────────────────────────────────
    // 每 30s 拉取最新 5 根（覆盖未收盘 K 线）
    // ──────────────────────────────────────────────────────────────
    @Scheduled(initialDelay = 20_000, fixedDelay = 30_000)
    public void fetchLatest() {
        WebClient client = webClientBuilder.baseUrl(BASE_URL).build();
        for (String symbol : SYMBOLS) {
            for (String bar : BARS) {
                try {
                    fetchAndSave(client, symbol, bar, 5);
                } catch (Exception e) {
                    log.warn("⚠️ K 线拉取失败 {}/{}: {}", symbol, bar, e.getMessage());
                }
                sleepMs(80);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 00:20 清理过期 K 线
    // ──────────────────────────────────────────────────────────────
    @Scheduled(cron = "0 20 0 * * *")
    public void cleanupOldKlines() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Integer> retentionDays = Map.of(
            "15m", 7,
            "1H",  30,
            "4H",  180,
            "1D",  730,
            "1W",  2000
        );
        int total = 0;
        for (Map.Entry<String, Integer> e : retentionDays.entrySet()) {
            String bar  = e.getKey();
            LocalDateTime cutoff = now.minusDays(e.getValue());
            int deleted = klineRepo.deleteByBarAndOpenTimeBefore(bar, cutoff);
            if (deleted > 0) {
                log.info("🗑️ K 线清理 bar={} cutoff={} deleted={}", bar, cutoff.toLocalDate(), deleted);
            }
            total += deleted;
        }
        log.info("🗑️ K 线清理完成，共删除 {} 条", total);
    }

    // ──────────────────────────────────────────────────────────────
    // 核心拉取方法
    // ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private int fetchAndSave(WebClient client, String symbol, String bar, int limit) {
        String instId = symbol + "-USDT";
        String url    = "/api/v5/market/candles?instId=" + instId + "&bar=" + bar + "&limit=" + limit;

        Map<?, ?> resp;
        try {
            resp = client.get().uri(url).retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(8));
        } catch (Exception e) {
            log.warn("⚠️ OKX candles 超时 {}/{}", symbol, bar);
            return 0;
        }

        if (resp == null || !"0".equals(String.valueOf(resp.get("code")))) return 0;
        Object dataObj = resp.get("data");
        if (!(dataObj instanceof List<?> dataList) || dataList.isEmpty()) return 0;

        int saved = 0;
        for (Object rowObj : dataList) {
            if (!(rowObj instanceof List<?> row) || row.size() < 7) continue;
            try {
                long   tsMs      = Long.parseLong(String.valueOf(row.get(0)));
                LocalDateTime openTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(tsMs), ZoneId.of("Asia/Shanghai"));

                KlineBar kb = klineRepo.findBySymbolAndBarAndOpenTime(symbol, bar, openTime)
                        .orElseGet(() -> {
                            KlineBar n = new KlineBar();
                            n.setSymbol(symbol);
                            n.setBar(bar);
                            n.setOpenTime(openTime);
                            n.setCreatedAt(LocalDateTime.now());
                            return n;
                        });

                kb.setOpenPrice (parseBD(row.get(1)));
                kb.setHighPrice (parseBD(row.get(2)));
                kb.setLowPrice  (parseBD(row.get(3)));
                kb.setClosePrice(parseBD(row.get(4)));
                kb.setVolume    (parseBD(row.get(5)));
                kb.setVolumeUsdt(row.size() > 6 ? parseBD(row.get(6)) : null);
                kb.setConfirmed (row.size() > 8 && "1".equals(String.valueOf(row.get(8))));

                klineRepo.save(kb);
                saved++;
            } catch (Exception e) {
                log.debug("⚠️ K 线行解析失败 {}/{}: {}", symbol, bar, e.getMessage());
            }
        }
        return saved;
    }

    private static BigDecimal parseBD(Object obj) {
        if (obj == null) return BigDecimal.ZERO;
        try { return new BigDecimal(String.valueOf(obj)); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
