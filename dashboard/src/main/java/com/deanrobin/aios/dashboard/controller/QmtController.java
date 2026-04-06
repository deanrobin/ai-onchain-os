package com.deanrobin.aios.dashboard.controller;

import com.deanrobin.aios.dashboard.model.PriceTicker;
import com.deanrobin.aios.dashboard.repository.KlineBarRepository;
import com.deanrobin.aios.dashboard.repository.PriceTickerRepository;
import com.deanrobin.aios.dashboard.service.KlineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * QMT 行情策略看板 REST 接口。
 *
 * GET /api/qmt/data?bar=15m
 * 返回四个币种的当前价格 + 均线指标 + 策略信号。
 */
@RestController
@RequestMapping("/api/qmt")
@RequiredArgsConstructor
public class QmtController {

    private static final List<String> SYMBOLS = List.of("BTC", "ETH", "BNB", "SOL", "XAUT");
    private static final List<String> VALID_BARS =
            List.of("15m", "1H", "4H", "1D", "1W");

    private final PriceTickerRepository priceRepo;
    private final KlineBarRepository    klineRepo;
    private final KlineService          klineService;

    /**
     * 主数据接口：价格 + 均线 + 策略。
     *
     * @param bar 时间粒度：15m / 1H / 4H / 1D / 1W，默认 15m
     */
    @GetMapping("/data")
    public Map<String, Object> data(
            @RequestParam(defaultValue = "15m") String bar) {

        // 参数白名单校验
        if (!VALID_BARS.contains(bar)) bar = "15m";

        // ── 价格（从 DB 读，PriceFetchJob 每 10s 更新）────────────
        List<PriceTicker> tickers = priceRepo.findAllByOrderBySymbolAsc();
        List<Map<String, Object>> priceList = tickers.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol",    p.getSymbol());
            m.put("priceUsd",  p.getPriceUsd());
            m.put("change24h", p.getChange24h());
            m.put("volume24h", p.getVolume24h());
            m.put("updatedAt", p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null);
            return m;
        }).collect(Collectors.toList());

        // ── 均线 + 策略（从 kline_bar 计算）────────────────────
        Map<String, Map<String, Object>> indicators = new LinkedHashMap<>();
        for (String symbol : SYMBOLS) {
            indicators.put(symbol, klineService.analyze(symbol, bar));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tickers",    priceList);
        result.put("indicators", indicators);
        result.put("bar",        bar);
        result.put("validHours", KlineService.validHours(bar));
        result.put("serverTime", java.time.LocalDateTime.now().toString());
        return result;
    }

    /**
     * GET /api/qmt/status
     * 返回 K 线数据积累情况，供页面状态栏展示。
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        // 各 symbol × bar 的 K 线条数
        Map<String, Object> counts = new LinkedHashMap<>();
        long totalBars = 0;
        for (String symbol : SYMBOLS) {
            Map<String, Long> barCounts = new LinkedHashMap<>();
            for (String bar : VALID_BARS) {
                long cnt = klineRepo.countBySymbolAndBar(symbol, bar);
                barCounts.put(bar, cnt);
                totalBars += cnt;
            }
            counts.put(symbol, barCounts);
        }
        // 价格最后更新时间
        String priceUpdated = priceRepo.findBySymbol("BTC")
                .map(p -> p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null)
                .orElse(null);

        result.put("totalKlineBars",  totalBars);
        result.put("klineReady",      totalBars >= 80); // 至少有一些 15m 数据
        result.put("klineCounts",     counts);
        result.put("priceUpdatedAt",  priceUpdated);
        result.put("serverTime",      java.time.LocalDateTime.now().toString());
        return result;
    }
}
