package com.deanrobin.aios.dashboard.controller;

import com.deanrobin.aios.dashboard.model.BinanceTicker;
import com.deanrobin.aios.dashboard.model.PerpInstrument;
import com.deanrobin.aios.dashboard.model.PriceTicker;
import com.deanrobin.aios.dashboard.model.SmartMoneySignal;
import com.deanrobin.aios.dashboard.repository.BinanceTickerRepository;
import com.deanrobin.aios.dashboard.repository.PerpInstrumentRepository;
import com.deanrobin.aios.dashboard.repository.PerpOpenInterestRepository;
import com.deanrobin.aios.dashboard.repository.PriceTickerRepository;
import com.deanrobin.aios.dashboard.service.PerpAlertService;
import com.deanrobin.aios.dashboard.service.PerpService;
import com.deanrobin.aios.dashboard.service.PortfolioService;
import com.deanrobin.aios.dashboard.service.SmartMoneyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** REST endpoints for AJAX calls from frontend */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final PerpService                  perpService;
    private final PerpAlertService             perpAlertService;
    private final PerpInstrumentRepository     perpInstrumentRepo;
    private final PerpOpenInterestRepository   oiRepo;
    private final SmartMoneyService            smartMoneyService;
    private final com.deanrobin.aios.dashboard.repository.PumpTokenRepository          pumpTokenRepo;
    private final com.deanrobin.aios.dashboard.repository.PumpMarketCapSnapshotRepository snapshotRepo;
    private final com.deanrobin.aios.dashboard.service.PumpPortalClient                pumpPortalClient;
    private final com.deanrobin.aios.dashboard.repository.FourMemeTokenRepository      fourMemeRepo;
    private final com.deanrobin.aios.dashboard.service.FourMemeClient                  fourMemeClient;
    private final PortfolioService        portfolioService;
    private final PriceTickerRepository   priceRepo;
    private final BinanceTickerRepository binanceTickerRepo;

    /** 最新四币价格（从 DB 读，PriceFetchJob 每 30s 更新） */
    @GetMapping("/prices")
    public List<Map<String, Object>> prices() {
        return priceRepo.findAllByOrderBySymbolAsc().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol",    p.getSymbol());
            m.put("priceUsd",  p.getPriceUsd());
            m.put("change24h", p.getChange24h());
            m.put("updatedAt", p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null);
            return m;
        }).collect(Collectors.toList());
    }

    private static final DateTimeFormatter SIG_FMT =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    /** 从 DB 读最新信号，供首页 AJAX 刷新使用（含格式化时间字符串） */
    @GetMapping("/signals/db")
    public List<Map<String, Object>> signalsFromDb(
            @RequestParam(defaultValue = "20") int limit) {
        return smartMoneyService.getRecentSignals(null, limit).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tokenSymbol",       s.getTokenSymbol());
            m.put("tokenAddress",      s.getTokenAddress());
            m.put("chainIndex",        s.getChainIndex());
            m.put("amountUsd",         s.getAmountUsd());
            m.put("triggerWalletCount",s.getTriggerWalletCount());
            m.put("signalTimeStr",  s.getSignalTime() != null ? s.getSignalTime().format(SIG_FMT) : "—");
            m.put("marketCapUsd",   s.getMarketCapUsd());
            m.put("priceAtSignal",  s.getPriceAtSignal());
            return m;
        }).collect(Collectors.toList());
    }

    @GetMapping("/signals")
    public List<?> signals(
            @RequestParam(defaultValue = "1") String chain,
            @RequestParam(defaultValue = "1") String walletType) {
        return smartMoneyService.fetchLiveSignals(chain, walletType);
    }

    @GetMapping("/wallets")
    public List<?> wallets(@RequestParam(defaultValue = "20") int limit) {
        return smartMoneyService.getTopWallets(limit);
    }

    @GetMapping("/portfolio/overview")
    public Map<?, ?> portfolioOverview(
            @RequestParam String address,
            @RequestParam(defaultValue = "1") String chain,
            @RequestParam(defaultValue = "3") String timeFrame) {
        return portfolioService.getOverview(chain, address, timeFrame);
    }

    @GetMapping("/portfolio/pnl")
    public List<?> portfolioPnl(
            @RequestParam String address,
            @RequestParam(defaultValue = "1") String chain,
            @RequestParam(defaultValue = "20") String limit) {
        return portfolioService.getRecentPnl(chain, address, limit);
    }

    @GetMapping("/portfolio/txs")
    public Map<?, ?> portfolioTxs(
            @RequestParam String address,
            @RequestParam(defaultValue = "1") String chains,
            @RequestParam(defaultValue = "30") String limit,
            @RequestParam(defaultValue = "0") String offset) {
        return portfolioService.getTxHistory(address, chains, limit, offset);
    }

    @GetMapping("/wallet/overview")
    public Map<?, ?> walletOverview(
            @RequestParam String address,
            @RequestParam(defaultValue = "1") String chain,
            @RequestParam(defaultValue = "3") String timeFrame) {
        return smartMoneyService.getWalletOverview(chain, address, timeFrame);
    }

    @GetMapping("/pump/tokens")
    public Object pumpTokens(@RequestParam(defaultValue = "100") int limit) {
        var list = pumpTokenRepo.findRecent(Math.min(limit, 200));
        return list.stream().map(t -> {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("mint",          t.getMint());
            m.put("name",          t.getName());
            m.put("symbol",        t.getSymbol());
            m.put("description",   t.getDescription());
            m.put("imageUri",      t.getImageUri());
            m.put("twitter",       t.getTwitter());
            m.put("telegram",      t.getTelegram());
            m.put("website",       t.getWebsite());
            m.put("creator",       t.getCreator());
            m.put("usdMarketCap",  t.getUsdMarketCap());
            m.put("marketCapSol",  t.getMarketCapSol());
            m.put("progress",      t.getProgress());
            m.put("initialBuy",    t.getInitialBuy());
            m.put("receivedAt",    t.getReceivedAt() != null
                    ? t.getReceivedAt().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) : "—");
            return m;
        }).toList();
    }

    @GetMapping("/pump/status")
    public Map<String, Object> pumpStatus() {
        return Map.of(
            "connected", pumpPortalClient.isConnected(),
            "total",     pumpTokenRepo.count()
        );
    }

    @GetMapping("/fourmeme/tokens")
    public Object fourMemeTokens(@RequestParam(defaultValue = "100") int limit) {
        // 取 BNB 当前价格
        java.math.BigDecimal bnbPrice = null;
        try {
            bnbPrice = pumpTokenRepo.findBnbPrice();
        } catch (Exception ignored) {}
        final java.math.BigDecimal finalBnbPrice = bnbPrice;

        return fourMemeRepo.findRecent(Math.min(limit, 200)).stream().map(t -> {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("tokenAddress", t.getTokenAddress());
            m.put("name",         t.getName());
            m.put("symbol",       t.getShortName());
            m.put("capBnb",       t.getCapBnb());
            // capBnb × BNB价 → USD
            if (t.getCapBnb() != null && finalBnbPrice != null) {
                m.put("usdMarketCap", t.getCapBnb().multiply(finalBnbPrice)
                        .setScale(2, java.math.RoundingMode.HALF_UP));
            } else {
                m.put("usdMarketCap", null);
            }
            m.put("progress",     t.getProgress());
            m.put("hold",         t.getHold());
            m.put("img",          t.getImg());
            m.put("receivedAt",   t.getReceivedAt() != null
                    ? t.getReceivedAt().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) : "—");
            return m;
        }).toList();
    }

    // ─── Perps ────────────────────────────────────────────────────────
    /**
     * GET /api/perps/featured
     * 返回三所 BTC + ETH 的最新资金费率，供页面顶部固定展示区使用。
     */
    @GetMapping("/perps/featured")
    public Map<String, Object> perpFeatured() {
        // 各交易所 BTC/ETH 的原始 symbol
        var okxSymbols  = List.of("BTC-USDT-SWAP", "ETH-USDT-SWAP");
        var bnSymbols   = List.of("BTCUSDT", "ETHUSDT");
        var hlSymbols   = List.of("BTC", "ETH");

        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : List.of(
                Map.entry("OKX",         okxSymbols),
                Map.entry("BINANCE",     bnSymbols),
                Map.entry("HYPERLIQUID", hlSymbols))) {
            String exch = entry.getKey();
            List<String> syms = entry.getValue();
            List<PerpInstrument> found = perpInstrumentRepo.findFeatured(exch, syms);
            Map<String, Object> exchMap = new LinkedHashMap<>();
            for (String target : syms) {
                found.stream()
                     .filter(p -> target.equals(p.getSymbol()))
                     .findFirst()
                     .ifPresentOrElse(
                         p -> exchMap.put(p.getBaseCurrency() != null ? p.getBaseCurrency() : target,
                                         toRateMap(p)),
                         () -> exchMap.put(target, null)
                     );
            }
            result.put(exch, exchMap);
        }
        return result;
    }
    /**
     * GET /api/perps/rates?exchange=OKX
     * 返回指定交易所最新费率 top5高 / top5低，供页面 AJAX 自动刷新。
     */
    @GetMapping("/perps/rates")
    public Map<String, Object> perpRates(@RequestParam(defaultValue = "OKX") String exchange) {
        String ex = exchange.toUpperCase();
        List<Map<String, Object>> high = perpService.getTop10High(ex).stream()
                .map(ApiController::toRateMap).collect(Collectors.toList());
        List<Map<String, Object>> low  = perpService.getTop10Low(ex).stream()
                .map(ApiController::toRateMap).collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exchange",    ex);
        result.put("topHigh",     high);
        result.put("topLow",      low);
        result.put("total",       perpService.getInstrumentCount(ex));
        result.put("updatedAt",   java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")));
        return result;
    }

    /**
     * POST /api/perps/report
     * 立刻发送三所 Top5 飞书汇报。5 分钟内只能触发一次。
     */
    @PostMapping("/perps/report")
    public Map<String, Object> perpReport() {
        boolean sent = perpAlertService.triggerManualReport();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok",  sent);
        r.put("msg", sent ? "已发送到飞书" : "5 分钟内已发送过，请稍后再试");
        return r;
    }

    private static Map<String, Object> toRateMap(PerpInstrument p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol",      p.getSymbol());
        m.put("base",        p.getBaseCurrency());
        m.put("rate",        p.getLatestFundingRate());
        m.put("isWatched",   Boolean.TRUE.equals(p.getIsWatched()));
        m.put("updatedAt",   p.getLatestFundingUpdatedAt() != null
                ? p.getLatestFundingUpdatedAt().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                : "—");
        return m;
    }

    // ════════════════════════════════════════════════════════════════
    // 合约持仓量 (OI)
    // ════════════════════════════════════════════════════════════════

    /**
     * GET /api/oi/binance
     * 返回 Binance watched 品种最新 OI 快照 + 15min / 4H 变化百分比。
     */
    @GetMapping("/oi/binance")
    public List<Map<String, Object>> binanceOI() {
        var latest = oiRepo.findLatestPerSymbol("BINANCE");
        var dtFmt  = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
        return latest.stream().map(snap -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol",   snap.getSymbol());
            m.put("oiUsdt",   snap.getOiUsdt());
            m.put("oiCoin",   snap.getOiCoin());
            double oiUsdt = snap.getOiUsdt() != null ? snap.getOiUsdt().doubleValue() : 0;
            java.time.LocalDateTime t = snap.getFetchedAt();
            m.put("delta15m", calcOiChangePct(snap.getSymbol(), t.minusMinutes(20), t.minusMinutes(10), oiUsdt));
            m.put("delta4h",  calcOiChangePct(snap.getSymbol(), t.minusHours(5),   t.minusHours(3),    oiUsdt));
            m.put("fetchedAt", t != null ? t.format(dtFmt) : "—");
            return m;
        }).toList();
    }

    private Double calcOiChangePct(String symbol, java.time.LocalDateTime from,
                                   java.time.LocalDateTime to, double currentOiUsdt) {
        return oiRepo.findEarliestInRange("BINANCE", symbol, from, to).map(prev -> {
            double prevUsdt = prev.getOiUsdt() != null ? prev.getOiUsdt().doubleValue() : 0;
            if (prevUsdt <= 0) return null;
            return (currentOiUsdt - prevUsdt) / prevUsdt * 100;
        }).orElse(null);
    }

    @GetMapping("/fourmeme/status")
    public Map<String, Object> fourMemeStatus() {
        return Map.of("connected", fourMemeClient.isConnected(), "total", fourMemeRepo.count());
    }

    @GetMapping("/fourmeme/survivors")
    public Object fourMemeSurvivors() {
        return fourMemeRepo.findSurvivors().stream().map(t -> {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("tokenAddress",    t.getTokenAddress());
            m.put("name",            t.getName());
            m.put("symbol",          t.getShortName());
            m.put("img",             t.getImg());
            m.put("currentMarketCap",t.getCurrentMarketCap());
            m.put("lastCheckedAt",   t.getLastCheckedAt() != null
                    ? t.getLastCheckedAt().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) : "—");
            m.put("receivedAt",      t.getReceivedAt() != null
                    ? t.getReceivedAt().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) : "—");
            return m;
        }).toList();
    }

    @GetMapping("/pump/survivors")
    public Object pumpSurvivors() {
        var list = pumpTokenRepo.findSurvivors();
        return list.stream().map(t -> {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("mint",             t.getMint());
            m.put("name",             t.getName());
            m.put("symbol",           t.getSymbol());
            m.put("imageUri",         t.getImageUri());
            m.put("currentMarketCap", t.getCurrentMarketCap());
            m.put("lastCheckedAt",    t.getLastCheckedAt() != null
                    ? t.getLastCheckedAt().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) : "—");
            m.put("receivedAt",       t.getReceivedAt() != null
                    ? t.getReceivedAt().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) : "—");
            // 最近 5 条快照
            var snaps = snapshotRepo.findByMintRecent(t.getMint(), 5);
            m.put("snapshots", snaps.stream().map(s -> Map.of(
                "mc",  s.getMarketCapUsd(),
                "at",  s.getCheckedAt().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            )).toList());
            return m;
        }).toList();
    }

    // ════════════════════════════════════════════════════════════════
    // Binance 合约行情
    // ════════════════════════════════════════════════════════════════

    /**
     * GET /api/ticker/binance?sort=volume|gainers|losers
     * 返回 Top20 合约行情，支持三种排序。
     */
    @GetMapping("/ticker/binance")
    public Map<String, Object> binanceTicker(
            @RequestParam(defaultValue = "volume") String sort) {
        List<BinanceTicker> list = switch (sort) {
            case "gainers" -> binanceTickerRepo.findTop20ByGainers();
            case "losers"  -> binanceTickerRepo.findTop20ByLosers();
            default        -> binanceTickerRepo.findTop20ByVolume();
        };
        List<Map<String, Object>> rows = list.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol",         t.getSymbol());
            m.put("base",           t.getBaseCurrency());
            m.put("price",          t.getLastPrice());
            m.put("changePct",      t.getPriceChangePct());
            m.put("quoteVolume",    t.getQuoteVolume());
            m.put("tradeCount",     t.getTradeCount());
            m.put("fetchedAt",      t.getFetchedAt() != null
                    ? t.getFetchedAt().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) : "—");
            return m;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sort",      sort);
        result.put("total",     binanceTickerRepo.count());
        result.put("rows",      rows);
        result.put("updatedAt", java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")));
        return result;
    }
}
