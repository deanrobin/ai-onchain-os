package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.config.BtcKlineLoaderConfig;
import com.deanrobin.aios.dashboard.repository.BtcKline15mRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用启动后一次性拉取 Binance BTCUSDT 15m 历史 K 线。
 *
 * 策略：
 *   1) 取 cfg.start-time / cfg.end-time 作为目标窗口
 *   2) 去重：读 DB max(openTime)，取 max(cfg.start, maxOpenTime + 15min) 作为实际起点
 *      （force-reload=true 时忽略 max，回到 cfg.start）
 *   3) 为了计算 MA120 / MACD(12,26,9) / RSI21，从实际起点再往前多拉 200 根 warmup
 *   4) 分页拉取（Binance /api/v3/klines limit=1000），每批间隔 cfg.rate-limit-ms
 *   5) 基于完整序列计算指标，然后 INSERT IGNORE 批量写入（靠 uk_open_time 保证幂等）
 *
 * 不加 @Transactional（单批 batchUpdate 自身是一次事务）。
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class BtcKlineLoaderJob {

    private static final String  BINANCE_BASE = "https://api.binance.com";
    private static final String  KLINE_URI    = "/api/v3/klines";
    private static final String  SYMBOL       = "BTCUSDT";
    private static final String  INTERVAL     = "15m";
    private static final long    MS_15M       = 15L * 60 * 1000;
    private static final int     BATCH_LIMIT  = 1000;                // Binance 单次最大
    private static final int     WARMUP_BARS  = 200;                 // MA120/MACD warmup
    private static final int     INSERT_BATCH = 500;
    private static final ZoneId  ZONE         = ZoneId.of("Asia/Shanghai");

    private final BtcKlineLoaderConfig    cfg;
    private final BtcKline15mRepository   klineRepo;
    private final JdbcTemplate            jdbc;
    private final WebClient.Builder       webClientBuilder;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!cfg.isEnabled()) {
            log.info("⏸ btc-kline.loader.enabled=false，跳过历史导入");
            return;
        }
        // 独立线程跑，避免阻塞 Spring Boot 启动
        Thread t = new Thread(this::runLoad, "btc-kline-loader");
        t.setDaemon(true);
        t.start();
    }

    private void runLoad() {
        LocalDateTime start, end;
        try {
            start = LocalDateTime.parse(cfg.getStartTime());
            end   = LocalDateTime.parse(cfg.getEndTime());
        } catch (Exception e) {
            log.error("❌ btc-kline.loader 时间解析失败 start={} end={}: {}",
                    cfg.getStartTime(), cfg.getEndTime(), e.getMessage());
            return;
        }
        if (!end.isAfter(start)) {
            log.warn("⚠️ btc-kline.loader end 必须在 start 之后，跳过");
            return;
        }

        // ── 去重：计算实际起点 ──────────────────────────────
        LocalDateTime effectiveStart = start;
        if (!cfg.isForceReload()) {
            LocalDateTime maxOpen = klineRepo.findMaxOpenTime();
            if (maxOpen != null && !maxOpen.isBefore(start)) {
                effectiveStart = maxOpen.plusMinutes(15);
                log.info("⏭️ DB 已有最新 K 线 at {}，增量从 {} 开始", maxOpen, effectiveStart);
            }
        }
        if (!effectiveStart.isBefore(end)) {
            log.info("✅ BTC K 线已覆盖目标窗口 [{}, {}), 无需导入", start, end);
            return;
        }

        // 带 warmup 的拉取起点
        LocalDateTime warmupStart = effectiveStart.minusMinutes(15L * WARMUP_BARS);

        log.info("🚀 BTC K 线加载开始 symbol={} interval={} effective=[{}, {}) warmupFrom={} forceReload={}",
                SYMBOL, INTERVAL, effectiveStart, end, warmupStart, cfg.isForceReload());

        List<Kline> bars = fetchAll(warmupStart, end);
        if (bars.isEmpty()) {
            log.warn("⚠️ 未从 Binance 拉到任何 K 线");
            return;
        }
        log.info("📥 Binance 已拉取 {} 根原始 K 线", bars.size());

        computeIndicators(bars);
        int inserted = bulkInsert(bars, effectiveStart);

        log.info("✅ BTC K 线加载完成 pulled={} inserted={} dedupSkip={}",
                bars.size(), inserted, bars.size() - inserted);
    }

    // ──────────────────────────────────────────────────────────────
    // Binance /api/v3/klines 分页拉取
    // ──────────────────────────────────────────────────────────────
    private List<Kline> fetchAll(LocalDateTime fromLdt, LocalDateTime toLdt) {
        long fromMs = fromLdt.atZone(ZONE).toInstant().toEpochMilli();
        long toMs   = toLdt.atZone(ZONE).toInstant().toEpochMilli();

        WebClient client = webClientBuilder.clone()
                .baseUrl(BINANCE_BASE)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                        .build())
                .build();

        List<Kline> out = new ArrayList<>();
        long cursor = fromMs;
        int page = 0, failStreak = 0;

        while (cursor < toMs) {
            page++;
            final long startMs = cursor;
            final long endMs   = Math.min(cursor + BATCH_LIMIT * MS_15M, toMs);

            List<List<Object>> raw;
            try {
                raw = client.get()
                        .uri(u -> u.path(KLINE_URI)
                                .queryParam("symbol",    SYMBOL)
                                .queryParam("interval",  INTERVAL)
                                .queryParam("startTime", startMs)
                                .queryParam("endTime",   endMs)
                                .queryParam("limit",     BATCH_LIMIT)
                                .build())
                        .retrieve()
                        .bodyToMono(new ParameterizedTypeReference<List<List<Object>>>() {})
                        .timeout(Duration.ofSeconds(20))
                        .block();
                failStreak = 0;
            } catch (Exception e) {
                failStreak++;
                long backoff = Math.min(30_000L, 1_000L * (1L << Math.min(failStreak, 5)));
                log.warn("⚠️ Binance /klines 失败 page={} cursor={} 第{}次 退避{}ms: {}",
                        page, startMs, failStreak, backoff, e.getMessage());
                if (failStreak >= 6) {
                    log.error("❌ 连续 6 次失败，中止本次加载。已拉 {} 根，稍后重启可续传", out.size());
                    return out;
                }
                sleep(backoff);
                continue;
            }

            if (raw == null || raw.isEmpty()) {
                log.info("ℹ️ 无更多 K 线（cursor={}）", startMs);
                break;
            }

            for (List<Object> row : raw) {
                try {
                    Kline k = new Kline();
                    k.openTimeMs  = ((Number) row.get(0)).longValue();
                    k.open        = new BigDecimal(String.valueOf(row.get(1)));
                    k.high        = new BigDecimal(String.valueOf(row.get(2)));
                    k.low         = new BigDecimal(String.valueOf(row.get(3)));
                    k.close       = new BigDecimal(String.valueOf(row.get(4)));
                    k.volume      = new BigDecimal(String.valueOf(row.get(5)));
                    k.quoteVolume = row.size() > 7  ? new BigDecimal(String.valueOf(row.get(7))) : null;
                    k.tradeCount  = row.size() > 8  ? ((Number) row.get(8)).intValue()            : null;
                    out.add(k);
                } catch (Exception e) {
                    log.warn("⚠️ 跳过解析异常行: {}", e.getMessage());
                }
            }

            long lastOpen = ((Number) raw.get(raw.size() - 1).get(0)).longValue();
            cursor = lastOpen + MS_15M;

            if (page % 10 == 0) {
                log.info("  ... 进度 page={} 累计={} 根 cursor={}",
                        page, out.size(), Instant.ofEpochMilli(cursor).atZone(ZONE).toLocalDateTime());
            }
            sleep(cfg.getRateLimitMs());
        }
        return out;
    }

    // ──────────────────────────────────────────────────────────────
    // 技术指标：MA20, MA120, MACD(12,26,9), RSI21 (Wilder 平滑)
    // ──────────────────────────────────────────────────────────────
    private void computeIndicators(List<Kline> bars) {
        int n = bars.size();
        if (n == 0) return;

        double[] close = new double[n];
        for (int i = 0; i < n; i++) close[i] = bars.get(i).close.doubleValue();

        // MA20 / MA120 — 滑动窗口和
        double sum20 = 0, sum120 = 0;
        for (int i = 0; i < n; i++) {
            sum20 += close[i];
            if (i >= 20) sum20 -= close[i - 20];
            if (i >= 19) bars.get(i).ma20 = round8(sum20 / 20.0);

            sum120 += close[i];
            if (i >= 120) sum120 -= close[i - 120];
            if (i >= 119) bars.get(i).ma120 = round8(sum120 / 120.0);
        }

        // MACD(12,26,9) — 标准 EMA 平滑 α = 2/(N+1)
        double a12 = 2.0 / 13.0;
        double a26 = 2.0 / 27.0;
        double a9  = 2.0 / 10.0;
        double ema12 = close[0], ema26 = close[0], dea = 0;
        for (int i = 0; i < n; i++) {
            ema12 = (i == 0) ? close[0] : a12 * close[i] + (1 - a12) * ema12;
            ema26 = (i == 0) ? close[0] : a26 * close[i] + (1 - a26) * ema26;
            double dif = ema12 - ema26;
            dea = (i == 0) ? dif : a9 * dif + (1 - a9) * dea;
            if (i >= 25) {
                bars.get(i).macdDif  = round8(dif);
                bars.get(i).macdDea  = round8(dea);
                bars.get(i).macdHist = round8((dif - dea) * 2.0);
            }
        }

        // RSI21 — Wilder 平滑
        final int rsiN = 21;
        if (n > rsiN) {
            double avgGain = 0, avgLoss = 0;
            for (int i = 1; i <= rsiN; i++) {
                double d = close[i] - close[i - 1];
                if (d >= 0) avgGain += d; else avgLoss += -d;
            }
            avgGain /= rsiN;
            avgLoss /= rsiN;
            bars.get(rsiN).rsi21 = rsiFrom(avgGain, avgLoss);
            for (int i = rsiN + 1; i < n; i++) {
                double d = close[i] - close[i - 1];
                double g = d >= 0 ? d : 0;
                double l = d <  0 ? -d : 0;
                avgGain = (avgGain * (rsiN - 1) + g) / rsiN;
                avgLoss = (avgLoss * (rsiN - 1) + l) / rsiN;
                bars.get(i).rsi21 = rsiFrom(avgGain, avgLoss);
            }
        }
    }

    private static BigDecimal rsiFrom(double avgGain, double avgLoss) {
        if (avgLoss == 0) return BigDecimal.valueOf(100);
        double rs  = avgGain / avgLoss;
        double rsi = 100 - 100.0 / (1 + rs);
        return BigDecimal.valueOf(rsi).setScale(4, java.math.RoundingMode.HALF_UP);
    }
    private static BigDecimal round8(double v) {
        return BigDecimal.valueOf(v).setScale(8, java.math.RoundingMode.HALF_UP);
    }

    // ──────────────────────────────────────────────────────────────
    // 批量 INSERT IGNORE（依赖 uk_open_time 做行级去重）
    // ──────────────────────────────────────────────────────────────
    private int bulkInsert(List<Kline> bars, LocalDateTime effectiveStart) {
        long startMs = effectiveStart.atZone(ZONE).toInstant().toEpochMilli();

        String sql =
                "INSERT IGNORE INTO btc_kline_15m " +
                "(open_time, open_price, high_price, low_price, close_price, volume, " +
                " quote_volume, trade_count, ma20, ma120, macd_dif, macd_dea, macd_hist, rsi21, source) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'binance')";

        List<Object[]> batch = new ArrayList<>(INSERT_BATCH);
        int inserted = 0;

        for (Kline k : bars) {
            if (k.openTimeMs < startMs) continue;   // 跳过 warmup 段
            LocalDateTime openTime = Instant.ofEpochMilli(k.openTimeMs).atZone(ZONE).toLocalDateTime();
            batch.add(new Object[]{
                    openTime, k.open, k.high, k.low, k.close, k.volume,
                    k.quoteVolume, k.tradeCount,
                    k.ma20, k.ma120, k.macdDif, k.macdDea, k.macdHist, k.rsi21
            });
            if (batch.size() >= INSERT_BATCH) {
                inserted += countAffected(jdbc.batchUpdate(sql, batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            inserted += countAffected(jdbc.batchUpdate(sql, batch));
        }
        return inserted;
    }

    private static int countAffected(int[] arr) {
        int c = 0;
        for (int v : arr) if (v > 0) c += v;
        return c;
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // Binance 原始 K 线 + 指标，内部用 POJO
    private static class Kline {
        long openTimeMs;
        BigDecimal open, high, low, close, volume, quoteVolume;
        Integer tradeCount;
        BigDecimal ma20, ma120, macdDif, macdDea, macdHist, rsi21;
    }
}
