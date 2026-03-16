package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.model.SmartMoneyWallet;
import com.deanrobin.aios.dashboard.model.WalletTxCache;
import com.deanrobin.aios.dashboard.repository.SmartMoneyWalletRepository;
import com.deanrobin.aios.dashboard.repository.WalletTxCacheRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 钱包详情缓存服务：
 * - Web 触发调用
 * - 缓存未过期 → 直接返回 DB 数据（不打 OKX）
 * - 缓存过期   → 调 OKX API → 写 DB → 返回
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class WalletCacheService {

    // TTL 从 yml 注入，可配置
    @Value("${smart-money.cache.overview-ttl-seconds:300}")
    private int overviewTtlSeconds;

    @Value("${smart-money.cache.tx-history-ttl-seconds:30}")
    private int txHistoryTtlSeconds;

    @Value("${smart-money.cache.tx-history-limit:50}")
    private int txHistoryLimit;

    private static final String OVERVIEW_PATH  = "/api/v6/dex/market/portfolio/overview";
    private static final String TX_HISTORY_PATH = "/api/v6/dex/post-transaction/transactions-by-address";
    private static final DateTimeFormatter TX_FMT =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private final OkxApiClient              okxClient;
    private final SmartMoneyWalletRepository walletRepo;
    private final WalletTxCacheRepository   txCacheRepo;
    private final ObjectMapper              objectMapper;

    // ─────────────────────────────────────────────────────────────
    // Overview：缓存 overviewTtlSeconds，过期则调 OKX 刷新
    // ─────────────────────────────────────────────────────────────
    public Map<String, Object> getOverview(String address, String chain, String timeFrame) {
        SmartMoneyWallet wallet = walletRepo
            .findByAddressAndChainIndex(address, chain).orElse(null);

        boolean cacheValid = wallet != null
            && wallet.getOverviewJson() != null
            && wallet.getOverviewUpdatedAt() != null
            && wallet.getOverviewUpdatedAt().isAfter(
                LocalDateTime.now().minusSeconds(overviewTtlSeconds));

        if (cacheValid) {
            log.debug("📦 overview 命中缓存 addr={} ttl={}s", address.substring(0, 8), overviewTtlSeconds);
            return parseOverviewJson(wallet.getOverviewJson());
        }

        // 缓存过期或无缓存 → 调 OKX
        log.info("🌐 overview 缓存过期，调 OKX addr={} chain={}", address.substring(0, 8), chain);
        Map<String, Object> fresh = fetchOverviewFromOkx(address, chain, timeFrame);
        if (!fresh.isEmpty()) {
            saveOverviewCache(address, chain, fresh, wallet);
        }
        return fresh;
    }

    // ─────────────────────────────────────────────────────────────
    // Tx History：缓存 txHistoryTtlSeconds，过期则调 OKX 刷新
    // ─────────────────────────────────────────────────────────────
    public List<WalletTxCache> getTxHistory(String address, String chain) {
        List<WalletTxCache> cached = txCacheRepo
            .findByAddressAndChainIndexOrderByTxTimeDesc(address, chain);

        boolean cacheValid = !cached.isEmpty()
            && cached.get(0).getCreatedAt() != null
            && cached.get(0).getCreatedAt().isAfter(
                LocalDateTime.now().minusSeconds(txHistoryTtlSeconds));

        if (cacheValid) {
            log.debug("📦 tx_history 命中缓存 addr={} rows={}", address.substring(0, 8), cached.size());
            return cached;
        }

        log.info("🌐 tx_history 缓存过期，调 OKX addr={} chain={}", address.substring(0, 8), chain);
        List<WalletTxCache> fresh = fetchTxHistoryFromOkx(address, chain);
        return fresh.isEmpty() ? cached : fresh; // 拉失败时降级返回旧缓存
    }

    // ─────────────────────────────────────────────────────────────
    // 私有方法
    // ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchOverviewFromOkx(String address, String chain, String timeFrame) {
        try {
            Map<?, ?> resp = okxClient.getWeb3(OVERVIEW_PATH, Map.of(
                "chainIndex", chain,
                "address",    address,
                "timeFrame",  timeFrame
            ));
            if (resp == null || !"0".equals(String.valueOf(resp.get("code")))) return Map.of();
            Object data = resp.get("data");
            Map<?, ?> raw = null;
            if (data instanceof List<?> l && !l.isEmpty()) raw = (Map<?, ?>) l.get(0);
            else if (data instanceof Map<?, ?> m)          raw = m;
            if (raw == null) return Map.of();
            // 转成 Map<String,Object>
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        } catch (Exception e) {
            log.warn("⚠️ fetchOverview 失败 addr={}: {}", address.substring(0, 8), e.getMessage());
            return Map.of();
        }
    }

    private void saveOverviewCache(String address, String chain,
                                   Map<String, Object> data,
                                   SmartMoneyWallet wallet) {
        try {
            String json = objectMapper.writeValueAsString(data);
            if (wallet == null) {
                wallet = new SmartMoneyWallet();
                wallet.setAddress(address);
                wallet.setChainIndex(chain);
                wallet.setCreatedAt(LocalDateTime.now());
            }
            wallet.setOverviewJson(json);
            wallet.setOverviewUpdatedAt(LocalDateTime.now());
            wallet.setUpdatedAt(LocalDateTime.now());
            walletRepo.save(wallet);
        } catch (Exception e) {
            log.warn("⚠️ 写 overview 缓存失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<WalletTxCache> fetchTxHistoryFromOkx(String address, String chain) {
        try {
            Map<?, ?> resp = okxClient.getWeb3(TX_HISTORY_PATH, Map.of(
                "address", address,
                "chains",  chain,
                "limit",   String.valueOf(txHistoryLimit)
            ));
            if (resp == null || !"0".equals(String.valueOf(resp.get("code")))) return List.of();

            Object txData = resp.get("data");
            List<?> txList = null;
            if (txData instanceof Map<?,?> txMap) txList = (List<?>) txMap.get("transactions");
            else if (txData instanceof List<?> l)  txList = l;
            if (txList == null || txList.isEmpty()) return List.of();

            return saveTxCache(address, chain, txList);
        } catch (Exception e) {
            log.warn("⚠️ fetchTxHistory 失败 addr={}: {}", address.substring(0, 8), e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<WalletTxCache> saveTxCache(String address, String chain, List<?> txList) {
        String explorer = "56".equals(chain)  ? "https://bscscan.com/tx/"
                        : "501".equals(chain) ? "https://solscan.io/tx/"
                        : "https://etherscan.io/tx/";
        String addrLower = address.toLowerCase();
        txCacheRepo.deleteByAddressAndChainIndex(address, chain);

        List<WalletTxCache> rows = new ArrayList<>();
        for (Object item : txList) {
            if (!(item instanceof Map<?,?> tx)) continue;
            WalletTxCache row = new WalletTxCache();
            row.setAddress(address);
            row.setChainIndex(chain);
            // 时间
            Object tsObj = tx.get("txTime");
            if (tsObj != null) {
                try {
                    long ts = Long.parseLong(String.valueOf(tsObj));
                    row.setTxTime(ts);
                    row.setDisplayTime(TX_FMT.format(Instant.ofEpochMilli(ts)));
                } catch (Exception ignored) { row.setDisplayTime("—"); }
            }
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
            if (toObj instanceof List<?> toList && !toList.isEmpty()
                    && toList.get(0) instanceof Map<?,?> toMap) {
                Object toAddr = toMap.get("address");
                incoming = toAddr != null && String.valueOf(toAddr).toLowerCase().contains(addrLower);
            }
            row.setIncoming(incoming);
            row.setSuccess("success".equals(tx.get("txStatus")));
            // Hash
            Object hash = tx.get("txHash");
            String hashStr = hash != null ? String.valueOf(hash) : "";
            row.setTxHash(hashStr);
            row.setExplorerUrl(explorer + hashStr);
            row.setCreatedAt(LocalDateTime.now());
            rows.add(row);
        }
        return txCacheRepo.saveAll(rows);
    }

    // 解析已存 JSON
    private Map<String, Object> parseOverviewJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
