package com.deanrobin.aios.dashboard.controller;

import com.deanrobin.aios.dashboard.service.PortfolioService;
import com.deanrobin.aios.dashboard.service.SmartMoneyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** REST endpoints for AJAX calls from frontend */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final SmartMoneyService smartMoneyService;
    private final PortfolioService portfolioService;

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
            @RequestParam(defaultValue = "20") String limit) {
        return portfolioService.getTxHistory(address, chains, limit);
    }

    @GetMapping("/wallet/overview")
    public Map<?, ?> walletOverview(
            @RequestParam String address,
            @RequestParam(defaultValue = "1") String chain,
            @RequestParam(defaultValue = "3") String timeFrame) {
        return smartMoneyService.getWalletOverview(chain, address, timeFrame);
    }
}
