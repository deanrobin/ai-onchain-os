package com.deanrobin.aios.dashboard.controller;

import com.deanrobin.aios.dashboard.model.MyAddress;
import com.deanrobin.aios.dashboard.model.SmartMoneyWallet;
import com.deanrobin.aios.dashboard.model.WalletTxCache;
import com.deanrobin.aios.dashboard.repository.MyAddressRepository;
import com.deanrobin.aios.dashboard.repository.SmartMoneyWalletRepository;
import com.deanrobin.aios.dashboard.repository.WalletTxCacheRepository;
import com.deanrobin.aios.dashboard.service.PortfolioService;
import com.deanrobin.aios.dashboard.service.SmartMoneyService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.deanrobin.aios.dashboard.vo.OverviewVO;
import com.deanrobin.aios.dashboard.vo.TxRowVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Log4j2
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final SmartMoneyService smartMoneyService;
    private final PortfolioService portfolioService;
    private final MyAddressRepository myAddressRepo;
    private final SmartMoneyWalletRepository walletRepo;
    private final WalletTxCacheRepository txCacheRepo;
    private final ObjectMapper objectMapper;

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

    private static final DateTimeFormatter TX_FMT =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

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
        model.addAttribute("chainName", chain.equals("56") ? "BSC" : chain.equals("501") ? "SOL" : "ETH");

        // 概览：从 DB 读 overview_json，不调 OKX
        SmartMoneyWallet wallet = walletRepo
            .findByAddressAndChainIndex(address, chain).orElse(null);
        Map<String, Object> rawOverview = Map.of();
        if (wallet != null && wallet.getOverviewJson() != null) {
            try {
                rawOverview = objectMapper.readValue(wallet.getOverviewJson(),
                    new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {}
        }
        model.addAttribute("ov", OverviewVO.from(rawOverview));
        model.addAttribute("hasOverview", !rawOverview.isEmpty());

        // 交易记录：30秒内有缓存直接用，否则按需从 OKX 拉一次
        List<WalletTxCache> cachedTxs = txCacheRepo
            .findByAddressAndChainIndexOrderByTxTimeDesc(address, chain);
        boolean cacheStale = cachedTxs.isEmpty() ||
            cachedTxs.get(0).getCreatedAt().isBefore(LocalDateTime.now().minusSeconds(30));
        if (cacheStale) {
            try {
                Map<?, ?> txData = portfolioService.getTxHistory(address, chain, "50");
                Object txRaw = txData.get("transactions");
                if (txRaw instanceof List<?> freshList && !freshList.isEmpty()) {
                    cachedTxs = buildAndSaveTxCache(address, chain, freshList);
                }
            } catch (Exception e) {
                log.warn("⚠️ 按需抓取 tx_history 失败 addr={}: {}", address.substring(0,10), e.getMessage());
            }
        }
        List<TxRowVO> txRows = cachedTxs.stream().map(tx -> {
            TxRowVO row = new TxRowVO();
            row.setDisplayTime(tx.getDisplayTime() != null ? tx.getDisplayTime() : "—");
            row.setTypeLabel(tx.getTypeLabel() != null ? tx.getTypeLabel() : "—");
            row.setSymbol(tx.getSymbol() != null ? tx.getSymbol() : "—");
            row.setAmount(tx.getAmount() != null ? tx.getAmount() : "—");
            row.setIncoming(Boolean.TRUE.equals(tx.getIncoming()));
            row.setSuccess(Boolean.TRUE.equals(tx.getSuccess()));
            String hash = tx.getTxHash() != null ? tx.getTxHash() : "";
            row.setTxHash(hash);
            row.setTxHashShort(hash.length() > 12 ? hash.substring(0, 12) + "..." : hash);
            row.setExplorerUrl(tx.getExplorerUrl() != null ? tx.getExplorerUrl() : "#");
            return row;
        }).collect(java.util.stream.Collectors.toList());
        model.addAttribute("txList", txRows);

        // 浏览器链接前缀
        String explorer = chain.equals("56") ? "https://bscscan.com/tx/"
                        : chain.equals("501") ? "https://solscan.io/tx/"
                        : "https://etherscan.io/tx/";
        model.addAttribute("explorer", explorer);

        return "wallet-detail";
    }

    /** 把 OKX 原始 txList 存入 DB 缓存并返回 WalletTxCache 列表 */
    @SuppressWarnings("unchecked")
    private List<WalletTxCache> buildAndSaveTxCache(String address, String chain, List<?> txList) {
        String explorer = chain.equals("56") ? "https://bscscan.com/tx/"
                        : chain.equals("501") ? "https://solscan.io/tx/"
                        : "https://etherscan.io/tx/";
        String addrLower = address.toLowerCase();
        txCacheRepo.deleteByAddressAndChainIndex(address, chain);
        List<WalletTxCache> rows = new ArrayList<>();
        for (Object item : txList) {
            if (!(item instanceof Map<?,?> tx)) continue;
            WalletTxCache row = new WalletTxCache();
            row.setAddress(address);
            row.setChainIndex(chain);
            Object tsObj = tx.get("txTime");
            if (tsObj != null) {
                try {
                    long ts = Long.parseLong(String.valueOf(tsObj));
                    row.setTxTime(ts);
                    row.setDisplayTime(TX_FMT.format(Instant.ofEpochMilli(ts)));
                } catch (Exception e) { row.setDisplayTime("—"); }
            }
            Object itypeObj = tx.get("itype");
            String itype = itypeObj != null ? String.valueOf(itypeObj) : "";
            row.setTypeLabel("2".equals(itype) ? "Token转账" : "0".equals(itype) ? "主链币" : "合约调用");
            Object sym = tx.get("symbol"); row.setSymbol(sym != null ? String.valueOf(sym) : "—");
            Object amt = tx.get("amount"); row.setAmount(amt != null ? String.valueOf(amt) : "—");
            boolean incoming = false;
            Object toObj = tx.get("to");
            if (toObj instanceof List<?> toList && !toList.isEmpty() && toList.get(0) instanceof Map<?,?> toMap) {
                Object toAddr = toMap.get("address");
                incoming = toAddr != null && String.valueOf(toAddr).toLowerCase().contains(addrLower);
            }
            row.setIncoming(incoming);
            row.setSuccess("success".equals(tx.get("txStatus")));
            Object hash = tx.get("txHash"); String hashStr = hash != null ? String.valueOf(hash) : "";
            row.setTxHash(hashStr);
            row.setExplorerUrl(explorer + hashStr);
            row.setCreatedAt(LocalDateTime.now());
            rows.add(row);
        }
        return txCacheRepo.saveAll(rows);
    }

    @SuppressWarnings("unchecked")
    private List<TxRowVO> buildTxRows(Object txRaw, String addrLower, String chain) {
        if (!(txRaw instanceof List<?> list)) return List.of();
        String explorer = chain.equals("56") ? "https://bscscan.com/tx/"
                        : chain.equals("501") ? "https://solscan.io/tx/"
                        : "https://etherscan.io/tx/";
        List<TxRowVO> rows = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?,?> tx)) continue;
            TxRowVO row = new TxRowVO();

            // 时间
            Object tsObj = tx.get("txTime");
            if (tsObj != null) {
                try {
                    long ts = Long.parseLong(String.valueOf(tsObj));
                    row.setDisplayTime(TX_FMT.format(Instant.ofEpochMilli(ts)));
                } catch (Exception e) { row.setDisplayTime("—"); }
            } else { row.setDisplayTime("—"); }

            // 类型
            Object itypeObj = tx.get("itype");
            String itype = itypeObj != null ? String.valueOf(itypeObj) : "";
            row.setTypeLabel("2".equals(itype) ? "Token转账" : "0".equals(itype) ? "主链币" : "合约调用");

            // 代币/数量
            Object sym = tx.get("symbol");
            row.setSymbol(sym != null ? String.valueOf(sym) : "—");
            Object amt = tx.get("amount");
            row.setAmount(amt != null ? String.valueOf(amt) : "—");

            // 方向
            boolean incoming = false;
            Object toObj = tx.get("to");
            if (toObj instanceof List<?> toList && !toList.isEmpty()) {
                Object first = toList.get(0);
                if (first instanceof Map<?,?> toMap) {
                    Object toAddr = toMap.get("address");
                    incoming = toAddr != null && String.valueOf(toAddr).toLowerCase().contains(addrLower);
                }
            }
            row.setIncoming(incoming);

            // 状态
            row.setSuccess("success".equals(tx.get("txStatus")));

            // Hash
            Object hash = tx.get("txHash");
            String hashStr = hash != null ? String.valueOf(hash) : "";
            row.setTxHash(hashStr);
            row.setTxHashShort(hashStr.length() > 12 ? hashStr.substring(0, 12) + "..." : hashStr);
            row.setExplorerUrl(explorer + hashStr);

            rows.add(row);
        }
        return rows;
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

            // 7D 概览 → 转 VO，模板只做展示
            @SuppressWarnings("unchecked")
            Map<String, Object> overviewRaw = (Map<String, Object>) (Map<?,?>) portfolioService.getOverview(
                    addr.getChainIndex(), addr.getAddress(), "3");
            entry.put("ov", OverviewVO.from(overviewRaw));

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
