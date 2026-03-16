package com.deanrobin.aios.dashboard.controller;

import com.deanrobin.aios.dashboard.model.MyAddress;
import com.deanrobin.aios.dashboard.model.WalletTxCache;
import com.deanrobin.aios.dashboard.repository.MyAddressRepository;
import com.deanrobin.aios.dashboard.service.PortfolioService;
import com.deanrobin.aios.dashboard.service.SmartMoneyService;
import com.deanrobin.aios.dashboard.service.WalletCacheService;
import com.deanrobin.aios.dashboard.vo.OverviewVO;
import com.deanrobin.aios.dashboard.vo.TxRowVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Log4j2
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final SmartMoneyService   smartMoneyService;
    private final PortfolioService    portfolioService;
    private final WalletCacheService  walletCacheService;   // 详情页缓存服务
    private final MyAddressRepository myAddressRepo;

    // ──────────────────────────────────────────────────────────────
    // 首页：数据来自 DB（Job 已写好），不打 OKX
    // ──────────────────────────────────────────────────────────────
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("activePage", "index");
        model.addAttribute("topWallets", smartMoneyService.getTopWallets(20));
        model.addAttribute("recentSignals", smartMoneyService.getRecentSignals(null, 20));
        return "index";
    }

    // ──────────────────────────────────────────────────────────────
    // 聪明钱看板：数据来自 DB（Job 已写好），不打 OKX
    // ──────────────────────────────────────────────────────────────
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

    // ──────────────────────────────────────────────────────────────
    // 钱包详情：Web 触发，走 WalletCacheService 缓存层
    //   - overview:    缓存 TTL = smart-money.cache.overview-ttl-seconds
    //   - tx_history:  缓存 TTL = smart-money.cache.tx-history-ttl-seconds
    //   - 缓存有效 → 直接读 DB；缓存过期 → 调 OKX API 写 DB 再返回
    // ──────────────────────────────────────────────────────────────
    @GetMapping("/smart-money/{address}")
    public String walletDetail(
            @PathVariable String address,
            @RequestParam(defaultValue = "56") String chain,
            @RequestParam(defaultValue = "3")  String timeFrame,
            Model model) {

        model.addAttribute("activePage", "smart-money");
        model.addAttribute("address", address);
        model.addAttribute("chain", chain);
        model.addAttribute("timeFrame", timeFrame);
        model.addAttribute("chainName", chainName(chain));
        model.addAttribute("explorer", explorerPrefix(chain));

        // Overview：WalletCacheService 决定是用缓存还是调 OKX
        Map<String, Object> rawOverview = walletCacheService.getOverview(address, chain, timeFrame);
        model.addAttribute("ov", OverviewVO.from(rawOverview));
        model.addAttribute("hasOverview", !rawOverview.isEmpty());

        // Tx History：同上
        List<WalletTxCache> txCache = walletCacheService.getTxHistory(address, chain);
        model.addAttribute("txList", toTxRowVOs(txCache));

        return "wallet-detail";
    }

    // ──────────────────────────────────────────────────────────────
    // 我的持仓看板：overview 也走缓存服务
    // ──────────────────────────────────────────────────────────────
    @GetMapping("/portfolio")
    public String portfolio(Model model) {
        model.addAttribute("activePage", "portfolio");
        List<MyAddress> addresses = myAddressRepo.findByIsActiveTrue();
        List<Map<String, Object>> portfolioData = new ArrayList<>();

        for (MyAddress addr : addresses) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("address",    addr.getAddress());
            entry.put("label",      addr.getLabel());
            entry.put("chainIndex", addr.getChainIndex());

            // overview 走缓存（portfolio 页默认 7D = timeFrame 3）
            Map<String, Object> ov = walletCacheService.getOverview(
                    addr.getAddress(), addr.getChainIndex(), "3");
            entry.put("ov", OverviewVO.from(ov));

            // 近期 PnL（仍走 OKX，数据量小，可接受）
            List<?> pnl = portfolioService.getRecentPnl(
                    addr.getChainIndex(), addr.getAddress(), "10");
            entry.put("pnlList", pnl);

            portfolioData.add(entry);
        }

        model.addAttribute("portfolioData", portfolioData);
        model.addAttribute("hasAddresses", !addresses.isEmpty());
        return "portfolio";
    }

    // ──────────────────────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────────────────────

    private List<TxRowVO> toTxRowVOs(List<WalletTxCache> list) {
        return list.stream().map(tx -> {
            TxRowVO row = new TxRowVO();
            row.setDisplayTime(tx.getDisplayTime()  != null ? tx.getDisplayTime()  : "—");
            row.setTypeLabel  (tx.getTypeLabel()    != null ? tx.getTypeLabel()    : "—");
            row.setSymbol     (tx.getSymbol()       != null ? tx.getSymbol()       : "—");
            row.setAmount     (tx.getAmount()       != null ? tx.getAmount()       : "—");
            row.setIncoming   (Boolean.TRUE.equals(tx.getIncoming()));
            row.setSuccess    (Boolean.TRUE.equals(tx.getSuccess()));
            String hash = tx.getTxHash() != null ? tx.getTxHash() : "";
            row.setTxHash     (hash);
            row.setTxHashShort(hash.length() > 12 ? hash.substring(0, 12) + "..." : hash);
            row.setExplorerUrl(tx.getExplorerUrl()  != null ? tx.getExplorerUrl()  : "#");
            return row;
        }).collect(Collectors.toList());
    }

    private String chainName(String chain) {
        return switch (chain) { case "56" -> "BSC"; case "501" -> "SOL"; default -> "ETH"; };
    }

    private String explorerPrefix(String chain) {
        return switch (chain) {
            case "56"  -> "https://bscscan.com/tx/";
            case "501" -> "https://solscan.io/tx/";
            default    -> "https://etherscan.io/tx/";
        };
    }
}
