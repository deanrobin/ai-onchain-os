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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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
    // 买入信号专页：SSR 初始数据，前端 10 秒自动刷新
    // ──────────────────────────────────────────────────────────────
    @GetMapping("/signals")
    public String signals(Model model) {
        model.addAttribute("activePage", "signals");
        model.addAttribute("recentSignals", smartMoneyService.getRecentSignals(null, 50));
        return "signals";
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
    // 我的地址管理：查看 + 新增（最多 4 个）
    // ──────────────────────────────────────────────────────────────
    private static final int MAX_ADDRESSES = 4;
    // 支持的链（白名单校验，防止非法 chainIndex 注入）
    private static final java.util.Set<String> ALLOWED_CHAINS =
            java.util.Set.of("1", "56", "501", "8453");
    // 地址格式：0x 开头 hex（EVM）或 Base58 长度（Solana）
    private static final java.util.regex.Pattern ADDR_PATTERN =
            java.util.regex.Pattern.compile("^(0x[0-9a-fA-F]{40}|[1-9A-HJ-NP-Za-km-z]{32,44})$");

    @GetMapping("/my-addresses")
    public String myAddresses(Model model) {
        model.addAttribute("activePage", "portfolio");
        List<MyAddress> list = myAddressRepo.findAll();
        long total = list.size();
        model.addAttribute("addresses", list);
        model.addAttribute("total", total);
        model.addAttribute("canAdd", total < MAX_ADDRESSES);
        model.addAttribute("maxAddresses", MAX_ADDRESSES);
        return "my-addresses";
    }

    @PostMapping("/my-addresses/add")
    public String addAddress(
            @RequestParam String address,
            @RequestParam String chainIndex,
            @RequestParam(required = false, defaultValue = "") String label,
            RedirectAttributes ra) {

        // ── 输入校验（SQL 注入防护：JPA 参数化查询天然安全，这里额外做格式校验）
        String addr = address == null ? "" : address.trim();
        String chain = chainIndex == null ? "" : chainIndex.trim();
        String lbl   = label == null ? "" : label.trim();

        if (!ADDR_PATTERN.matcher(addr).matches()) {
            ra.addFlashAttribute("error", "地址格式不合法（仅支持 EVM 0x 地址或 Solana 地址）");
            return "redirect:/my-addresses";
        }
        if (!ALLOWED_CHAINS.contains(chain)) {
            ra.addFlashAttribute("error", "不支持的链，请选择 ETH/BSC/SOL/Base");
            return "redirect:/my-addresses";
        }
        if (lbl.length() > 50) {
            ra.addFlashAttribute("error", "备注最多 50 个字符");
            return "redirect:/my-addresses";
        }

        long total = myAddressRepo.countBy();
        if (total >= MAX_ADDRESSES) {
            ra.addFlashAttribute("error", "最多只能添加 " + MAX_ADDRESSES + " 个关注地址");
            return "redirect:/my-addresses";
        }
        if (myAddressRepo.existsByAddressAndChainIndex(addr, chain)) {
            ra.addFlashAttribute("error", "该地址已存在");
            return "redirect:/my-addresses";
        }

        MyAddress ma = new MyAddress();
        ma.setAddress(addr);
        ma.setChainIndex(chain);
        ma.setLabel(lbl.isEmpty() ? null : lbl);
        ma.setIsActive(true);
        ma.setCreatedAt(java.time.LocalDateTime.now());
        myAddressRepo.save(ma);

        log.info("➕ 新增关注地址 addr={} chain={} label={}", addr, chain, lbl);
        ra.addFlashAttribute("success", "添加成功：" + addr.substring(0, 10) + "...");
        return "redirect:/my-addresses";
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
