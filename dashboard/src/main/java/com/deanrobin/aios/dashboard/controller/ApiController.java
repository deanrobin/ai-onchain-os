package com.deanrobin.aios.dashboard.controller;

import com.deanrobin.aios.dashboard.model.SmartMoneySignal;
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

    private final SmartMoneyService smartMoneyService;
    private final PortfolioService portfolioService;

    private static final DateTimeFormatter SIG_FMT =
        DateTimeFormatter.ofPattern("MM-dd HH:mm");

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
            m.put("signalTimeStr",     s.getSignalTime() != null
                ? s.getSignalTime().format(SIG_FMT) : "—");
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
}
