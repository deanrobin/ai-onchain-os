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
    public Map<?, ?> getWalletOverview(String chainIndex, String address, String timeFrame) {
        try {
            Map<?, ?> resp = okx.getWeb3("/api/v6/dex/market/portfolio/overview", Map.of(
                    "chainIndex", chainIndex,
                    "walletAddress", address,
                    "timeFrame", timeFrame
            ));
            Object data = resp.get("data");
            if (data instanceof List<?> l && !l.isEmpty()) return (Map<?, ?>) l.get(0);
            return data instanceof Map<?, ?> m ? m : Map.of();
        } catch (Exception e) {
            log.warn("walletOverview failed: {}", e.getMessage());
            return Map.of();
        }
    }
}
