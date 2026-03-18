package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.PumpMarketCapSnapshot;
import com.deanrobin.aios.dashboard.model.PumpToken;
import com.deanrobin.aios.dashboard.repository.PumpMarketCapSnapshotRepository;
import com.deanrobin.aios.dashboard.repository.PumpTokenRepository;
import com.deanrobin.aios.dashboard.service.OkxApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Log4j2
@Component
@RequiredArgsConstructor
public class PumpSurvivorJob {

    private static final double MIN_MARKET_CAP = 10_000.0;
    private static final String CHAIN_SOL      = "501";
    private static final String TOKEN_DETAIL   = "/api/v5/wallet/token/token-detail";
    private static final String BASE_WEB3      = "https://www.okx.com";

    private final PumpTokenRepository          pumpTokenRepo;
    private final PumpMarketCapSnapshotRepository snapshotRepo;
    private final OkxApiClient                 okxApiClient;

    /** 每小时执行，初始延迟 10 分钟（等 OKX Job 先稳定） */
    @Scheduled(initialDelay = 600_000, fixedDelay = 3_600_000)
    @Transactional
    public void run() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<PumpToken> due = pumpTokenRepo.findDueForCheck(cutoff);
        if (due.isEmpty()) {
            log.info("🔍 PumpSurvivor: 无需检查的 token");
            return;
        }
        log.info("🔍 PumpSurvivor: 开始检查 {} 个超 24H token", due.size());

        int deleted = 0, survived = 0, failed = 0;
        for (int i = 0; i < due.size(); i++) {
            PumpToken t = due.get(i);
            // 除第一条外，每次查询前 sleep 5 秒
            if (i > 0) {
                try { Thread.sleep(5_000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                BigDecimal mc = fetchMarketCap(t.getMint());
                t.setLastCheckedAt(LocalDateTime.now());

                if (mc == null || mc.compareTo(BigDecimal.valueOf(MIN_MARKET_CAP)) < 0) {
                    // 市值 < 10K 或查不到 → 删除
                    pumpTokenRepo.delete(t);
                    deleted++;
                    log.info("🗑 删除低市值 token: {} ({}) mc={}", t.getSymbol(), t.getMint().substring(0, 10), mc);
                } else {
                    // 市值 >= 10K → 标记存活，保存快照
                    t.setStatus("survived");
                    t.setCurrentMarketCap(mc);
                    pumpTokenRepo.save(t);

                    PumpMarketCapSnapshot snap = new PumpMarketCapSnapshot();
                    snap.setMint(t.getMint());
                    snap.setSymbol(t.getSymbol());
                    snap.setName(t.getName());
                    snap.setMarketCapUsd(mc);
                    snap.setCheckedAt(LocalDateTime.now());
                    snapshotRepo.save(snap);

                    survived++;
                    log.info("✅ 存活 token: {} ({}) mc=${}", t.getSymbol(), t.getMint().substring(0, 10),
                            mc.toPlainString());
                }
            } catch (Exception e) {
                failed++;
                log.warn("⚠️ 检查 token {} 失败: {}", t.getMint().substring(0, 10), e.getMessage());
                // 出错时也更新检查时间，避免下次重复打爆
                t.setLastCheckedAt(LocalDateTime.now());
                pumpTokenRepo.save(t);
            }
        }
        log.info("🔍 PumpSurvivor 完成: 存活={} 删除={} 失败={}", survived, deleted, failed);
    }

    @SuppressWarnings("unchecked")
    private BigDecimal fetchMarketCap(String mint) {
        try {
            Map<?, ?> resp = okxApiClient.get(
                BASE_WEB3, TOKEN_DETAIL,
                Map.of("chainIndex", CHAIN_SOL, "tokenAddress", mint)
            );
            if (!"0".equals(String.valueOf(resp.get("code")))) return null;

            Object dataObj = resp.get("data");
            if (!(dataObj instanceof List<?> dataList) || dataList.isEmpty()) return null;

            Map<?, ?> item = (Map<?, ?>) dataList.get(0);

            // OKX 返回 marketCap 字段
            Object mc = item.get("marketCap");
            if (mc == null) mc = item.get("market_cap");
            if (mc == null) return null;

            BigDecimal val = new BigDecimal(mc.toString().replace(",", ""));
            return val.compareTo(BigDecimal.ZERO) <= 0 ? null : val;
        } catch (Exception e) {
            log.debug("fetchMarketCap {} error: {}", mint.substring(0, 10), e.getMessage());
            return null;
        }
    }
}
