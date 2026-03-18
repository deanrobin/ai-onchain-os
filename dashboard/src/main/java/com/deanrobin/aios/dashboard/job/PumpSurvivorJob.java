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

/**
 * Pump token 三阶段市值检查
 *
 * 10min 检查：mcap >= 10K → 标记 survived 并展示，否则仅记录检查时间
 *  1h  检查：同上
 * 24h  检查：mcap >= 10K → 标记 survived + 快照；< 10K → 删除
 *
 * Job 每 5 分钟轮一次，精确捡到三个时间节点。
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class PumpSurvivorJob {

    private static final double MIN_MARKET_CAP = 10_000.0;
    private static final String CHAIN_SOL      = "501";
    private static final String TOKEN_DETAIL   = "/api/v5/wallet/token/token-detail";
    private static final String BASE_WEB3      = "https://www.okx.com";
    private static final int    SLEEP_MS       = 5_000;

    private final PumpTokenRepository              pumpTokenRepo;
    private final com.deanrobin.aios.dashboard.repository.FourMemeTokenRepository fourMemeRepo;
    private final PumpMarketCapSnapshotRepository  snapshotRepo;
    private final OkxApiClient                     okxApiClient;

    /** 每 5 分钟轮一次，覆盖三个检查节点 */
    @Scheduled(initialDelay = 120_000, fixedDelay = 300_000)
    @Transactional
    public void run() {
        LocalDateTime now = LocalDateTime.now();

        // ── 10 分钟阶段 ──────────────────────────────────
        List<PumpToken> due10m = pumpTokenRepo.findDueFor10m(now.minusMinutes(10));
        if (!due10m.isEmpty()) {
            log.info("⏱ PumpSurvivor [10min] 检查 {} 个", due10m.size());
            processBatch(due10m, Stage.TEN_MIN);
        }

        // ── 1 小时阶段 ──────────────────────────────────
        List<PumpToken> due1h = pumpTokenRepo.findDueFor1h(now.minusHours(1));
        if (!due1h.isEmpty()) {
            log.info("⏱ PumpSurvivor [1h] 检查 {} 个", due1h.size());
            processBatch(due1h, Stage.ONE_HOUR);
        }

        // ── 24 小时阶段 ─────────────────────────────────
        List<PumpToken> due24h = pumpTokenRepo.findDueFor24h(now.minusHours(24));
        if (!due24h.isEmpty()) {
            log.info("⏱ PumpSurvivor [24h] 检查 {} 个", due24h.size());
            processBatch(due24h, Stage.TWENTY_FOUR_HOUR);
        }

        // ── FourMeme (BSC) 三阶段 ────────────────────
        processFourMemeBatch(fourMemeRepo.findDueFor10m(now.minusMinutes(10)),  Stage.TEN_MIN,           "56");
        processFourMemeBatch(fourMemeRepo.findDueFor1h(now.minusHours(1)),      Stage.ONE_HOUR,          "56");
        processFourMemeBatch(fourMemeRepo.findDueFor24h(now.minusHours(24)),    Stage.TWENTY_FOUR_HOUR,  "56");
    }

    private void processFourMemeBatch(List<com.deanrobin.aios.dashboard.model.FourMemeToken> tokens,
                                       Stage stage, String chainIndex) {
        if (tokens.isEmpty()) return;
        log.info("⏱ FourMeme [{}] 检查 {} 个", stage, tokens.size());
        int survived = 0, deleted = 0, skipped = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) { try { Thread.sleep(SLEEP_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } }
            var t = tokens.get(i);
            try {
                BigDecimal mc = fetchMarketCap(t.getTokenAddress(), chainIndex);
                LocalDateTime now = LocalDateTime.now();
                switch (stage) {
                    case TEN_MIN  -> t.setChecked10mAt(now);
                    case ONE_HOUR -> t.setChecked1hAt(now);
                    case TWENTY_FOUR_HOUR -> t.setLastCheckedAt(now);
                }
                boolean ok = mc != null && mc.compareTo(BigDecimal.valueOf(MIN_MARKET_CAP)) >= 0;
                if (ok) {
                    t.setStatus("survived"); t.setCurrentMarketCap(mc); fourMemeRepo.save(t); survived++;
                    log.info("✅ FourMeme [{}] 存活: {} mc=${}", stage, t.getShortName(), mc.toPlainString());
                } else if (stage == Stage.TWENTY_FOUR_HOUR) {
                    fourMemeRepo.delete(t); deleted++;
                } else {
                    fourMemeRepo.save(t); skipped++;
                }
            } catch (Exception e) {
                log.warn("⚠️ FourMeme [{}] 检查 {} 失败: {}", stage, t.getTokenAddress().substring(0, 10), e.getMessage());
                switch (stage) {
                    case TEN_MIN  -> t.setChecked10mAt(LocalDateTime.now());
                    case ONE_HOUR -> t.setChecked1hAt(LocalDateTime.now());
                    case TWENTY_FOUR_HOUR -> t.setLastCheckedAt(LocalDateTime.now());
                }
                fourMemeRepo.save(t);
            }
        }
        log.info("✔ FourMeme [{}] 完成: 存活={} 删除={} 跳过={}", stage, survived, deleted, skipped);
    }

    private enum Stage { TEN_MIN, ONE_HOUR, TWENTY_FOUR_HOUR }

    private void processBatch(List<PumpToken> tokens, Stage stage) {
        int survived = 0, deleted = 0, skipped = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                try { Thread.sleep(SLEEP_MS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
            }
            PumpToken t = tokens.get(i);
            try {
                BigDecimal mc = fetchMarketCap(t.getMint());
                LocalDateTime now = LocalDateTime.now();

                switch (stage) {
                    case TEN_MIN  -> t.setChecked10mAt(now);
                    case ONE_HOUR -> t.setChecked1hAt(now);
                    case TWENTY_FOUR_HOUR -> t.setLastCheckedAt(now);
                }

                boolean aboveMin = mc != null && mc.compareTo(BigDecimal.valueOf(MIN_MARKET_CAP)) >= 0;

                if (aboveMin) {
                    // 市值达标 → 标记存活
                    t.setStatus("survived");
                    t.setCurrentMarketCap(mc);
                    pumpTokenRepo.save(t);

                    // 只有 24H 阶段写快照（避免 3 个阶段都写）
                    if (stage == Stage.TWENTY_FOUR_HOUR) {
                        saveSnapshot(t, mc, now);
                    }
                    survived++;
                    log.info("✅ [{}] 存活: {} mc=${}", stage, t.getSymbol(), mc.toPlainString());
                } else if (stage == Stage.TWENTY_FOUR_HOUR) {
                    // 24H 阶段 + 不达标 → 删除
                    pumpTokenRepo.delete(t);
                    deleted++;
                    log.info("🗑 [24h] 删除: {} mc={}", t.getSymbol(), mc);
                } else {
                    // 10min / 1h 不达标 → 只记录检查时间，不删
                    pumpTokenRepo.save(t);
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("⚠️ [{}] 检查 {} 失败: {}", stage, t.getMint().substring(0, 10), e.getMessage());
                // 出错也记录检查时间，避免死循环
                switch (stage) {
                    case TEN_MIN  -> t.setChecked10mAt(LocalDateTime.now());
                    case ONE_HOUR -> t.setChecked1hAt(LocalDateTime.now());
                    case TWENTY_FOUR_HOUR -> t.setLastCheckedAt(LocalDateTime.now());
                }
                pumpTokenRepo.save(t);
            }
        }
        log.info("✔ [{}] 完成: 存活={} 删除={} 不达标跳过={}", stage, survived, deleted, skipped);
    }

    private void saveSnapshot(PumpToken t, BigDecimal mc, LocalDateTime at) {
        PumpMarketCapSnapshot snap = new PumpMarketCapSnapshot();
        snap.setMint(t.getMint());
        snap.setSymbol(t.getSymbol());
        snap.setName(t.getName());
        snap.setMarketCapUsd(mc);
        snap.setCheckedAt(at);
        snapshotRepo.save(snap);
    }

    private BigDecimal fetchMarketCap(String address) { return fetchMarketCap(address, CHAIN_SOL); }

    @SuppressWarnings("unchecked")
    private BigDecimal fetchMarketCap(String address, String chainIndex) {
        try {
            Map<?, ?> resp = okxApiClient.get(
                    BASE_WEB3, TOKEN_DETAIL,
                    Map.of("chainIndex", chainIndex, "tokenAddress", address)
            );
            if (!"0".equals(String.valueOf(resp.get("code")))) return null;
            Object dataObj = resp.get("data");
            if (!(dataObj instanceof List<?> dataList) || dataList.isEmpty()) return null;
            Map<?, ?> item = (Map<?, ?>) dataList.get(0);
            Object mc = item.get("marketCap");
            if (mc == null) mc = item.get("market_cap");
            if (mc == null) return null;
            BigDecimal val = new BigDecimal(mc.toString().replace(",", ""));
            return val.compareTo(BigDecimal.ZERO) <= 0 ? null : val;
        } catch (Exception e) {
            log.debug("fetchMarketCap {} error: {}", address.substring(0, 10), e.getMessage());
            return null;
        }
    }
}
