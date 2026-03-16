package com.deanrobin.aios.dashboard.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Fetches portfolio data (my own addresses) from OKX APIs.
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final OkxApiClient okx;

    /**
     * 地址画像概览: 胜率、盈亏、交易统计
     * timeFrame: 1=1D 2=3D 3=7D 4=1M 5=3M
     */
    public Map<?, ?> getOverview(String chainIndex, String address, String timeFrame) {
        try {
            Map<?, ?> resp = okx.getWeb3("/api/v6/dex/market/portfolio/overview", Map.of(
                    "chainIndex", chainIndex,
                    "walletAddress", address,
                    "timeFrame", timeFrame
            ));
            Object data = extractData(resp);
            return data instanceof Map<?, ?> m ? m : Map.of();
        } catch (Exception e) {
            log.warn("portfolio overview failed {}: {}", address, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 近期逐代币 PnL 列表
     */
    public List<?> getRecentPnl(String chainIndex, String address, String limit) {
        try {
            Map<?, ?> resp = okx.getWeb3("/api/v6/dex/market/portfolio/recent-pnl", Map.of(
                    "chainIndex", chainIndex,
                    "walletAddress", address,
                    "limit", limit
            ));
            Object data = extractData(resp);
            if (data instanceof Map<?, ?> m) {
                Object pnlList = m.get("pnlList");
                if (pnlList instanceof List<?> l) return l;
            }
            return List.of();
        } catch (Exception e) {
            log.warn("portfolio pnl failed {}: {}", address, e.getMessage());
            return List.of();
        }
    }

    /**
     * 交易历史
     */
    public Map<?, ?> getTxHistory(String address, String chains, String limit) {
        return getTxHistory(address, chains, limit, "0");
    }

    public Map<?, ?> getTxHistory(String address, String chains, String limit, String offset) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("address", address);
            params.put("chains", chains);
            params.put("limit", limit);
            if (!"0".equals(offset)) params.put("offset", offset);
            Map<?, ?> resp = okx.getWeb3(
                    "/api/v6/dex/post-transaction/transactions-by-address", params);
            Object data = extractData(resp);
            return data instanceof Map<?, ?> m ? m : Map.of();
        } catch (Exception e) {
            log.warn("tx history failed {}: {}", address, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 代币余额
     */
    @SuppressWarnings("unchecked")
    public List<?> getTokenBalances(String address, List<Map<String, String>> tokens) {
        try {
            Map<?, ?> resp = okx.post(
                    "https://www.okx.com",
                    "/api/v5/wallet/asset/token-balances-by-address",
                    Map.of("address", address, "tokenAddresses", tokens)
            );
            Object data = extractData(resp);
            if (data instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> m) {
                    Object assets = m.get("tokenAssets");
                    if (assets instanceof List<?>) return (List<?>) assets;
                }
            }
            return List.of();
        } catch (Exception e) {
            log.warn("token balances failed {}: {}", address, e.getMessage());
            return List.of();
        }
    }

    private Object extractData(Map<?, ?> resp) {
        if (resp == null) return Map.of();
        Object data = resp.get("data");
        if (data instanceof List<?> list && list.size() == 1) return list.get(0);
        return data != null ? data : Map.of();
    }
}
