package com.deanrobin.aios.dashboard.controller;

import com.deanrobin.aios.dashboard.model.PriceTicker;
import com.deanrobin.aios.dashboard.model.SmartMoneySignal;
import com.deanrobin.aios.dashboard.repository.PriceTickerRepository;
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

    private final SmartMoneyService         smartMoneyService;
    private final com.deanrobin.aios.dashboard.repository.PumpTokenRepository          pumpTokenRepo;
    private final com.deanrobin.aios.dashboard.repository.PumpMarketCapSnapshotRepository snapshotRepo;
    private final com.deanrobin.aios.dashboard.service.PumpPortalClient                pumpPortalClient;
    private final PortfolioService       portfolioService;
    private final PriceTickerRepository  priceRepo;

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
}
