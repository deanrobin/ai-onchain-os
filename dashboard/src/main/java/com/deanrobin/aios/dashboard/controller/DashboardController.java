package com.deanrobin.aios.dashboard.controller;

import com.deanrobin.aios.dashboard.model.MyAddress;
import com.deanrobin.aios.dashboard.repository.MyAddressRepository;
import com.deanrobin.aios.dashboard.service.PortfolioService;
import com.deanrobin.aios.dashboard.service.SmartMoneyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Log4j2
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final SmartMoneyService smartMoneyService;
    private final PortfolioService portfolioService;
    private final MyAddressRepository myAddressRepo;

    /** 首页：总览 */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("activePage", "index");
        model.addAttribute("topWallets", smartMoneyService.getTopWallets(30));
        model.addAttribute("recentSignals", smartMoneyService.getRecentSignals(null, 20));
        return "index";
    }

    /** 聪明钱看板 */
    @GetMapping("/smart-money")
    public String smartMoney(
            @RequestParam(defaultValue = "1") String chain,
            @RequestParam(defaultValue = "1") String walletType,
            Model model) {
        model.addAttribute("activePage", "smart-money");
        model.addAttribute("topWallets", smartMoneyService.getTopWallets(50));
        model.addAttribute("liveSignals", smartMoneyService.fetchLiveSignals(chain, walletType));
        model.addAttribute("chain", chain);
        model.addAttribute("walletType", walletType);
        return "smart-money";
    }

    /** 单个聪明钱详情 */
    @GetMapping("/smart-money/{address}")
    public String walletDetail(
            @PathVariable String address,
            @RequestParam(defaultValue = "1") String chain,
            @RequestParam(defaultValue = "3") String timeFrame,
            Model model) {
        model.addAttribute("activePage", "smart-money");
        model.addAttribute("address", address);
        model.addAttribute("chain", chain);
        model.addAttribute("timeFrame", timeFrame);
        model.addAttribute("overview", smartMoneyService.getWalletOverview(chain, address, timeFrame));
        // 交易记录（默认 30 条，倒序）
        Map<?, ?> txData = portfolioService.getTxHistory(address, chain, "30");
        Object txRaw = txData.get("transactions");
        model.addAttribute("txList", txRaw instanceof List<?> ? txRaw : List.of());
        return "wallet-detail";
    }

    /** 我的持仓看板 */
    @GetMapping("/portfolio")
    public String portfolio(Model model) {
        model.addAttribute("activePage", "portfolio");
        List<MyAddress> addresses = myAddressRepo.findByIsActiveTrue();
        List<Map<String, Object>> portfolioData = new ArrayList<>();

        for (MyAddress addr : addresses) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("address", addr.getAddress());
            entry.put("label", addr.getLabel());
            entry.put("chainIndex", addr.getChainIndex());

            // 7D 概览
            Map<?, ?> overview = portfolioService.getOverview(
                    addr.getChainIndex(), addr.getAddress(), "3");
            entry.put("overview", overview);

            // 近期 PnL（前5）
            List<?> pnl = portfolioService.getRecentPnl(
                    addr.getChainIndex(), addr.getAddress(), "10");
            entry.put("pnlList", pnl);

            portfolioData.add(entry);
        }

        model.addAttribute("portfolioData", portfolioData);
        model.addAttribute("hasAddresses", !addresses.isEmpty());
        return "portfolio";
    }
}
