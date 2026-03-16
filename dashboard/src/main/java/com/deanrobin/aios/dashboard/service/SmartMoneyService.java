package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.repository.SmartMoneyWalletRepository;
import com.deanrobin.aios.dashboard.repository.SmartMoneySignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.*;

@Log4j2
@Service
@RequiredArgsConstructor
public class SmartMoneyService {

    private final OkxApiClient okx;
    private final SmartMoneyWalletRepository walletRepo;
    private final SmartMoneySignalRepository signalRepo;

    /** 从 DB 获取聪明钱排行（按 score DESC）*/
    public List<?> getTopWallets(int limit) {
        return walletRepo.findTopByScoreDesc(limit);
    }

    /** 从 DB 获取最近信号 */
    public List<?> getRecentSignals(String chainIndex, int limit) {
        if (chainIndex != null && !chainIndex.isEmpty()) {
            return signalRepo.findRecentByChain(chainIndex, limit);
        }
        return signalRepo.findRecent(limit);
    }

    /** 实时拉 OKX Signal API */
    public List<?> fetchLiveSignals(String chainIndex, String walletType) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chainIndex", chainIndex);
            body.put("walletType", walletType);
            body.put("minAmountUsd", "5000");
            body.put("minAddressCount", "1");

            Map<?, ?> resp = okx.postWeb3("/api/v6/dex/market/signal/list", body);
            Object data = resp.get("data");
            return data instanceof List<?> l ? l : List.of();
        } catch (Exception e) {
            log.warn("fetchLiveSignals chain={}: {}", chainIndex, e.getMessage());
            return List.of();
        }
    }

    /** 单个钱包画像（实时）*/
    public Map<String, Object> getWalletOverview(String chainIndex, String address, String timeFrame) {
        try {
            Map<?, ?> resp = okx.getWeb3("/api/v6/dex/market/portfolio/overview", Map.of(
                    "chainIndex", chainIndex,
                    "walletAddress", address,
                    "timeFrame", timeFrame
            ));
            Object data = resp.get("data");
            Map<?, ?> raw = null;
            if (data instanceof List<?> l && !l.isEmpty()) raw = (Map<?, ?>) l.get(0);
            else if (data instanceof Map<?, ?> m) raw = m;
            return raw != null ? toTyped(raw) : Map.of();
        } catch (Exception e) {
            log.warn("walletOverview failed: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 把 OKX 原始 Map 中的数值字段从 String 转成 Double，方便模板直接做数值比较 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toTyped(Map<?, ?> raw) {
        Set<String> numericKeys = Set.of(
            "winRate","realizedPnlUsd","unrealizedPnlUsd","totalPnlUsd",
            "top3PnlTokenSumUsd","avgBuyValueUsd","buyTxCount","sellTxCount",
            "avgHoldingPeriod","top3PnlTokenAvgPnlPercent"
        );
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((k, v) -> {
            String key = String.valueOf(k);
            if (numericKeys.contains(key) && v != null) {
                try {
                    result.put(key, Double.parseDouble(String.valueOf(v)));
                } catch (NumberFormatException e) {
                    result.put(key, v);
                }
            } else {
                result.put(key, v);
            }
        });
        return result;
    }
}
