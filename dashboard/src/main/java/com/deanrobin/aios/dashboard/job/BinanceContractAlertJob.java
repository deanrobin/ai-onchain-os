package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.BinanceTicker;
import com.deanrobin.aios.dashboard.model.PerpInstrument;
import com.deanrobin.aios.dashboard.model.PerpOpenInterest;
import com.deanrobin.aios.dashboard.model.PerpSupplySnapshot;
import com.deanrobin.aios.dashboard.repository.BinanceTickerRepository;
import com.deanrobin.aios.dashboard.repository.PerpInstrumentRepository;
import com.deanrobin.aios.dashboard.repository.PerpOpenInterestRepository;
import com.deanrobin.aios.dashboard.repository.PerpSupplySnapshotRepository;
import com.deanrobin.aios.dashboard.service.PerpApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binance 合约行情增强报警 Job。
 *
 * <b>功能一：合约成交量异动报警（每 5 分钟）</b>
 *   - 对 is_watched=1 的 Binance 品种，拉取 22 根 1H K 线
 *   - 以最近 20 根已完成 K 线的 USDT 成交额为基准均量
 *   - 当前根成交额 > 基准均量 × 阈值 → 飞书报警
 *   - 报警内容：成交额(KW单位)、24h 涨跌幅、当前价格
 *
 * <b>功能二：持仓量(OI)快照 & 变化报警（每 5 分钟）</b>
 *   - 快照 OI（基础货币 + USDT 估值）写入 perp_open_interest
 *   - 计算 15 分钟 / 4 小时 OI 变化，附在成交量报警中展示
 *   - 每日 01:00 清理 7 天前的 OI 历史
 *
 * <b>功能三：代币供应量快照（每 12 小时）</b>
 *   - 从 CoinGecko 免费接口拉取前 500 个代币的供应量数据
 *     （Binance 公开 Futures API 不提供总量/流通量字段）
 *   - 存入 perp_supply_snapshot，报警时查 DB → 未命中再实时查 API
 *   - 用于计算：市值 = 价格 × 总量，流通市值 = 价格 × 流通量
 *
 * ⚠️ 不加 @Transactional（长任务禁止）
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class BinanceContractAlertJob {

    private static final String    EXCHANGE             = "BINANCE";
    /** 成交量报警阈值：当前 1H 成交额 > 均值 × 此倍数时触发 */
    private static final double    VOLUME_SPIKE_MULT    = 2.0;
    /** 成交量报警冷却（每个品种 1 小时内最多报一次） */
    private static final long      VOL_COOLDOWN_MS      = 3_600_000L;
    /** OI 报警阈值：15min 变化绝对值 > 此百分比时在报警中加高亮 */
    private static final double    OI_HIGHLIGHT_PCT     = 5.0;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final PerpApiClient              perpApiClient;
    private final PerpInstrumentRepository   instrumentRepo;
    private final PerpOpenInterestRepository oiRepo;
    private final PerpSupplySnapshotRepository supplyRepo;
    private final BinanceTickerRepository    binanceTickerRepo;
    private final WebClient.Builder          webClientBuilder;

    @Value("${perp.alert-url:}")
    private String alertUrl;

    /** 成交量报警冷却：key = "BINANCE:SYMBOL"，value = 上次报警时间戳(ms) */
    private final ConcurrentHashMap<String, Long> volCooldown = new ConcurrentHashMap<>();

    // ═════════════════════════════════════════════════════════════════
    // 功能一 & 二：成交量异动 + OI 快照（每 5 分钟）
    // ═════════════════════════════════════════════════════════════════

    @Scheduled(initialDelay = 180_000, fixedDelay = 300_000)
    public void checkVolumeAndSnapshotOI() {
        List<PerpInstrument> watched = instrumentRepo.findByIsWatchedTrue().stream()
                .filter(p -> EXCHANGE.equals(p.getExchange())).toList();
        if (watched.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        for (PerpInstrument inst : watched) {
            try {
                processSymbol(inst, now);
                sleepMs(100);
            } catch (Exception e) {
                log.warn("⚠️ BinanceContractAlert 处理 {} 失败: {}", inst.getSymbol(), e.getMessage());
            }
        }
    }

    private void processSymbol(PerpInstrument inst, LocalDateTime now) {
        String symbol = inst.getSymbol();

        // ── 1. 读 24h Ticker（从 DB，BinanceTickerJob 每分钟更新，无需额外 API 调用）
        double lastPrice, priceChangePct;
        var tickerOpt = binanceTickerRepo.findBySymbol(symbol);
        if (tickerOpt.isPresent()) {
            BinanceTicker t = tickerOpt.get();
            lastPrice      = t.getLastPrice()      != null ? t.getLastPrice().doubleValue()      : 0;
            priceChangePct = t.getPriceChangePct() != null ? t.getPriceChangePct().doubleValue() : 0;
        } else {
            // DB 未命中（极少发生）→ 回退 API
            Map<String, Object> ticker = perpApiClient.fetchBinanceTicker24h(symbol);
            lastPrice      = parseDouble(ticker.get("lastPrice"));
            priceChangePct = parseDouble(ticker.get("priceChangePercent"));
        }

        // ── 2. 拉 22 根 1H K 线（最后一根可能未收盘）───────────────────
        List<List<Object>> klines = perpApiClient.fetchBinanceKlines(symbol, "1h", 22);

        // ── 3. 快照 OI ───────────────────────────────────────────────
        OiInfo oiInfo = snapshotOI(symbol, lastPrice, now);

        // ── 4. 成交量异动检测 ────────────────────────────────────────
        if (klines.size() < 21) return;  // 数据不足，跳过

        // bars[0..19] = 20 根已完成 K 线（用于计算基准均量）
        // bars[20]   = 最近一根（当前进行中的 1H，实时成交额）
        double[] prevVols = new double[20];
        for (int i = 0; i < 20; i++) {
            prevVols[i] = parseDoubleAt(klines.get(i), 7);  // index7 = quoteVolume(USDT)
        }
        double avgVol     = Arrays.stream(prevVols).average().orElse(0);
        double currentVol = parseDoubleAt(klines.get(20), 7);

        if (avgVol <= 0 || currentVol <= avgVol * VOLUME_SPIKE_MULT) return;

        // ── 5. 冷却检查 ───────────────────────────────────────────
        String coolKey  = EXCHANGE + ":" + symbol;
        long   lastAlert = volCooldown.getOrDefault(coolKey, 0L);
        if (System.currentTimeMillis() - lastAlert < VOL_COOLDOWN_MS) return;
        volCooldown.put(coolKey, System.currentTimeMillis());

        // ── 6. 查供应量（DB → API 兜底） ────────────────────────────
        SupplyInfo supply = resolveSupply(inst.getBaseCurrency(), lastPrice);

        // ── 7. 发送飞书报警 ─────────────────────────────────────────
        sendVolumeAlert(symbol, currentVol, avgVol, lastPrice, priceChangePct, oiInfo, supply, now);
    }

    // ─── OI 快照 ─────────────────────────────────────────────────────
    private OiInfo snapshotOI(String symbol, double lastPrice, LocalDateTime now) {
        log.info("📡 查询 {} 持仓情况...", symbol);
        Map<String, Object> oiResp = perpApiClient.fetchBinanceOpenInterest(symbol);
        if (oiResp.isEmpty()) return null;

        double oiCoin = parseDouble(oiResp.get("openInterest"));
        double oiUsdt = oiCoin * lastPrice;

        PerpOpenInterest snap = new PerpOpenInterest();
        snap.setExchange(EXCHANGE);
        snap.setSymbol(symbol);
        snap.setOiCoin(BigDecimal.valueOf(oiCoin));
        snap.setOiUsdt(BigDecimal.valueOf(oiUsdt));
        snap.setFetchedAt(now);
        oiRepo.save(snap);

        // 计算 15 分钟变化
        OiDelta delta15m = calcOiDelta(symbol, now.minusMinutes(20), now.minusMinutes(10), oiUsdt);
        // 计算 4 小时变化
        OiDelta delta4h  = calcOiDelta(symbol, now.minusHours(5), now.minusHours(3), oiUsdt);

        return new OiInfo(oiUsdt, delta15m, delta4h);
    }

    private OiDelta calcOiDelta(String symbol, LocalDateTime from, LocalDateTime to, double currentOiUsdt) {
        return oiRepo.findEarliestInRange(EXCHANGE, symbol, from, to).map(prev -> {
            double prevUsdt  = prev.getOiUsdt() != null ? prev.getOiUsdt().doubleValue() : 0;
            if (prevUsdt <= 0) return null;
            double changePct = (currentOiUsdt - prevUsdt) / prevUsdt * 100;
            return new OiDelta(changePct, currentOiUsdt - prevUsdt);
        }).orElse(null);
    }

    // ─── 供应量查询（DB 优先，12H 内缓存有效，过期则实时拉 API）────────
    private SupplyInfo resolveSupply(String baseCurrency, double lastPrice) {
        if (baseCurrency == null || baseCurrency.isBlank()) return null;
        String upperCcy = baseCurrency.toUpperCase();

        // 查 DB 缓存
        Optional<PerpSupplySnapshot> cached = supplyRepo.findByBaseCurrency(upperCcy);
        if (cached.isPresent()) {
            PerpSupplySnapshot s = cached.get();
            boolean fresh = s.getFetchedAt().isAfter(LocalDateTime.now().minusHours(12));
            if (fresh) return toSupplyInfo(s, lastPrice);
        }

        // DB 未命中或已过期 → 实时从 CoinGecko 拉（只拉第 1 页，约 250 个最大市值代币）
        log.info("📡 supply cache miss for {}, 实时拉 CoinGecko", upperCcy);
        List<Map<String, Object>> coins = perpApiClient.fetchCoinGeckoMarkets(1);
        for (Map<String, Object> coin : coins) {
            String sym = String.valueOf(coin.getOrDefault("symbol", "")).toUpperCase();
            if (!upperCcy.equals(sym)) continue;
            PerpSupplySnapshot s = upsertSupply(coin, upperCcy);
            return toSupplyInfo(s, lastPrice);
        }
        return null;
    }

    private PerpSupplySnapshot upsertSupply(Map<String, Object> coin, String baseCurrency) {
        PerpSupplySnapshot s = supplyRepo.findByBaseCurrency(baseCurrency)
                .orElseGet(PerpSupplySnapshot::new);
        s.setBaseCurrency(baseCurrency);
        s.setCoingeckoId(String.valueOf(coin.getOrDefault("id", "")));
        s.setCirculatingSupply(parseBD(coin.get("circulating_supply")));
        s.setTotalSupply(parseBD(coin.get("total_supply")));
        s.setMaxSupply(parseBD(coin.get("max_supply")));
        s.setFetchedAt(LocalDateTime.now());
        return supplyRepo.save(s);
    }

    private SupplyInfo toSupplyInfo(PerpSupplySnapshot s, double price) {
        if (s.getCirculatingSupply() == null) return null;
        double circ  = s.getCirculatingSupply().doubleValue();
        double total = s.getTotalSupply() != null ? s.getTotalSupply().doubleValue() : 0;
        double maxS  = s.getMaxSupply()   != null ? s.getMaxSupply().doubleValue()   : 0;
        double mktCap     = circ  * price;
        double fdv        = total > 0 ? total * price : (maxS > 0 ? maxS * price : 0);
        return new SupplyInfo(circ, total > 0 ? total : maxS, mktCap, fdv);
    }

    // ─── 发飞书报警 ──────────────────────────────────────────────────
    private void sendVolumeAlert(String symbol, double currentVol, double avgVol,
                                 double price, double priceChangePct,
                                 OiInfo oi, SupplyInfo supply, LocalDateTime now) {
        if (alertUrl == null || alertUrl.isBlank()) return;

        double mult   = avgVol > 0 ? currentVol / avgVol : 0;
        String pctArrow = priceChangePct >= 0 ? "▲" : "▼";

        StringBuilder sb = new StringBuilder();
        sb.append("⚡ 合约成交量异动\n");
        sb.append("合约: ").append(symbol).append("\n");
        sb.append(String.format("成交额: %s (%.1f×均量)\n",
                formatKW(currentVol), mult));
        sb.append(String.format("24h涨跌: %s%+.2f%%\n", pctArrow, priceChangePct));
        sb.append(String.format("当前价格: %sU\n", formatPrice(price)));

        if (oi != null) {
            sb.append("─────────────\n");
            sb.append(String.format("持仓量(OI): %s\n", formatKW(oi.oiUsdt)));
            if (oi.delta15m != null) {
                String highlight = Math.abs(oi.delta15m.pct) >= OI_HIGHLIGHT_PCT ? " ⚠️" : "";
                sb.append(String.format("15min OI变化: %+.2f%%%s\n",
                        oi.delta15m.pct, highlight));
            }
            if (oi.delta4h != null) {
                sb.append(String.format("4H OI变化: %+.2f%%\n", oi.delta4h.pct));
            }
        }

        if (supply != null) {
            sb.append("─────────────\n");
            sb.append(String.format("流通市值: %s\n", formatUSD(supply.mktCap)));
            if (supply.fdv > 0) {
                sb.append(String.format("FDV(完全摊薄): %s\n", formatUSD(supply.fdv)));
            }
            sb.append(String.format("流通量: %s\n", formatAmount(supply.circulatingSupply)));
            if (supply.totalSupply > 0) {
                sb.append(String.format("总量: %s\n", formatAmount(supply.totalSupply)));
            }
        }

        sb.append(now.format(FMT));

        String text = sb.toString();
        log.info("⚡ 成交量异动报警 | {} | 成交额={} 涨跌={:+.2f}%",
                symbol, formatKW(currentVol), priceChangePct);

        try {
            Map<String, Object> body = Map.of("msg_type", "text",
                    "content", Map.of("text", text));
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

    // ═════════════════════════════════════════════════════════════════
    // 功能三：供应量批量刷新（每 12 小时）
    // ═════════════════════════════════════════════════════════════════

    @Scheduled(initialDelay = 60_000, fixedDelay = 43_200_000)  // 启动 1min 后首次运行，之后每 12H
    public void refreshSupplySnapshots() {
        log.info("📡 开始刷新 CoinGecko 供应量快照（前 500 个代币）...");
        int saved = 0;
        // 拉前 2 页（每页 250），覆盖市值前 500 的代币，基本涵盖所有 Binance 上市品种
        for (int page = 1; page <= 2; page++) {
            List<Map<String, Object>> coins = perpApiClient.fetchCoinGeckoMarkets(page);
            for (Map<String, Object> coin : coins) {
                try {
                    String sym = String.valueOf(coin.getOrDefault("symbol", "")).toUpperCase();
                    if (sym.isBlank() || sym.equals("NULL")) continue;
                    upsertSupply(coin, sym);
                    saved++;
                } catch (Exception e) {
                    log.debug("⚠️ supply upsert 失败: {}", e.getMessage());
                }
            }
            sleepMs(2000);  // CoinGecko 免费接口限速
        }
        log.info("📡 供应量快照刷新完成，共 upsert {} 条", saved);
    }

    // ═════════════════════════════════════════════════════════════════
    // 定期清理：OI 快照保留 7 天
    // ═════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 1 * * *")  // 每天 01:00
    public void cleanupOldOI() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        int deleted = oiRepo.deleteByFetchedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("🗑️ OI 历史清理 cutoff={} deleted={}", cutoff.toLocalDate(), deleted);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // 格式化工具
    // ═════════════════════════════════════════════════════════════════

    /**
     * 成交额/持仓量格式化为 KW 单位（千万，10^7）。
     * 例：57,800,000 → "5.78KWU"
     * 规则：≥10亿→亿U  |  ≥千万→KWU  |  ≥万→WU  |  其他→直接显示U
     */
    static String formatKW(double value) {
        if (value >= 1e9) {
            return String.format("%.2f亿U", value / 1e8);
        } else if (value >= 1e7) {
            return String.format("%.2fKWU", value / 1e7);
        } else if (value >= 1e4) {
            return String.format("%.2fWU", value / 1e4);
        } else {
            return String.format("%.2fU", value);
        }
    }

    /**
     * 市值格式化（与 formatKW 相同逻辑，语义更清晰）。
     */
    static String formatUSD(double value) {
        return formatKW(value);
    }

    /**
     * 供应量/数量格式化（不带 U 后缀）。
     * 例：19500000 → "1950.00万"  |  21000000000 → "210.00亿"
     */
    static String formatAmount(double value) {
        if (value >= 1e8) {
            return String.format("%.2f亿", value / 1e8);
        } else if (value >= 1e4) {
            return String.format("%.2f万", value / 1e4);
        } else {
            return String.format("%.0f", value);
        }
    }

    /**
     * 价格格式化：小价格保留更多小数位。
     * < 0.01  → 8位小数
     * < 1     → 4位小数
     * < 1000  → 2位小数
     * ≥ 1000  → 整数（带千分位）
     */
    static String formatPrice(double price) {
        if (price < 0.01)   return String.format("%.8f", price);
        if (price < 1)      return String.format("%.4f", price);
        if (price < 1000)   return String.format("%.2f", price);
        return String.format("%,.0f", price);
    }

    // ═════════════════════════════════════════════════════════════════
    // 私有工具
    // ═════════════════════════════════════════════════════════════════

    private double parseDouble(Object obj) {
        if (obj == null) return 0;
        try { return Double.parseDouble(String.valueOf(obj)); } catch (Exception e) { return 0; }
    }

    private double parseDoubleAt(List<Object> row, int index) {
        if (row == null || index >= row.size()) return 0;
        return parseDouble(row.get(index));
    }

    private BigDecimal parseBD(Object obj) {
        if (obj == null) return null;
        try { return new BigDecimal(String.valueOf(obj)); } catch (Exception e) { return null; }
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── 内部数据结构 ─────────────────────────────────────────────────

    private record OiDelta(double pct, double absoluteUsdt) {}

    private record OiInfo(double oiUsdt, OiDelta delta15m, OiDelta delta4h) {}

    private record SupplyInfo(double circulatingSupply, double totalSupply,
                               double mktCap, double fdv) {}
}
