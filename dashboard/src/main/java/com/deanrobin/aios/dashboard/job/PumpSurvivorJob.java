package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.PumpMarketCapSnapshot;
import com.deanrobin.aios.dashboard.model.PumpToken;
import com.deanrobin.aios.dashboard.repository.PumpMarketCapSnapshotRepository;
import com.deanrobin.aios.dashboard.repository.PumpTokenRepository;
import com.deanrobin.aios.dashboard.service.OkxApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.OptimisticLockingFailureException;
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
    public void run() {
        LocalDateTime now = LocalDateTime.now();

        // ── 积压清理（超过 2h 未到 1h 阶段 → 直接标记跳过；超过 6h 未到 4h → 跳过）──
        int skip10m = pumpTokenRepo.skipStale10m(now.minusHours(2));
        int skip1h  = pumpTokenRepo.skipStale1h(now.minusHours(6));
        int bskip10m = fourMemeRepo.skipStale10m(now.minusHours(2));
        int bskip1h  = fourMemeRepo.skipStale1h(now.minusHours(6));
        if (skip10m + skip1h + bskip10m + bskip1h > 0) {
            log.info("🧹 积压清理 SOL={}/{} BSC={}/{}", skip10m, skip1h, bskip10m, bskip1h);
        }

        // ── SOL (pump.fun) 八阶段 ────────────────────────────────────────────
        runStage(pumpTokenRepo.findDueFor10m(now.minusMinutes(10)),  Stage.TEN_MIN);
        runStage(pumpTokenRepo.findDueFor20m(now.minusMinutes(20)),  Stage.TWENTY_MIN);
        runStage(pumpTokenRepo.findDueFor30m(now.minusMinutes(30)),  Stage.THIRTY_MIN);
        runStage(pumpTokenRepo.findDueFor45m(now.minusMinutes(45)),  Stage.FORTY_FIVE_MIN);
        runStage(pumpTokenRepo.findDueFor1h(now.minusHours(1)),      Stage.ONE_HOUR);
        runStage(pumpTokenRepo.findDueFor4h(now.minusHours(4)),      Stage.FOUR_HOUR);
        runStage(pumpTokenRepo.findDueFor12h(now.minusHours(12)),    Stage.TWELVE_HOUR);
        runStage(pumpTokenRepo.findDueFor24h(now.minusHours(24)),    Stage.TWENTY_FOUR_HOUR);

        // ── BSC (four.meme) 八阶段 ───────────────────────────────────────────
        processFourMemeBatch(fourMemeRepo.findDueFor10m(now.minusMinutes(10)),  Stage.TEN_MIN,          "56");
        processFourMemeBatch(fourMemeRepo.findDueFor20m(now.minusMinutes(20)),  Stage.TWENTY_MIN,       "56");
        processFourMemeBatch(fourMemeRepo.findDueFor30m(now.minusMinutes(30)),  Stage.THIRTY_MIN,       "56");
        processFourMemeBatch(fourMemeRepo.findDueFor45m(now.minusMinutes(45)),  Stage.FORTY_FIVE_MIN,   "56");
        processFourMemeBatch(fourMemeRepo.findDueFor1h(now.minusHours(1)),      Stage.ONE_HOUR,         "56");
        processFourMemeBatch(fourMemeRepo.findDueFor4h(now.minusHours(4)),      Stage.FOUR_HOUR,        "56");
        processFourMemeBatch(fourMemeRepo.findDueFor12h(now.minusHours(12)),    Stage.TWELVE_HOUR,      "56");
        processFourMemeBatch(fourMemeRepo.findDueFor24h(now.minusHours(24)),    Stage.TWENTY_FOUR_HOUR, "56");
    }

    private void processFourMemeBatch(List<com.deanrobin.aios.dashboard.model.FourMemeToken> tokens,
                                       Stage stage, String chainIndex) {
        if (tokens.isEmpty()) return;
        log.info("⏱ FourMeme [{}] 检查 {} 个", stage, tokens.size());
        int survived = 0, deleted = 0, skipped = 0, conflicts = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) { try { Thread.sleep(SLEEP_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } }
            var t = tokens.get(i);
            try {
                BigDecimal mc = fetchMarketCap(t.getTokenAddress(), chainIndex);
                LocalDateTime now = LocalDateTime.now();
                markFourMemeStageTime(t, stage, now);
                boolean ok = mc != null && mc.compareTo(BigDecimal.valueOf(MIN_MARKET_CAP)) >= 0;
                if (ok) {
                    t.setStatus("survived"); t.setCurrentMarketCap(mc);
                    fourMemeRepo.save(t); survived++;
                    log.info("✅ FourMeme [{}] 存活: {} mc=${}", stage, t.getShortName(), mc.toPlainString());
                } else if (stage == Stage.TWENTY_FOUR_HOUR) {
                    fourMemeRepo.delete(t); deleted++;
                } else {
                    fourMemeRepo.save(t); skipped++;
                }
            } catch (OptimisticLockingFailureException e) {
                conflicts++;
                log.debug("🔀 FourMeme [{}] 版本冲突跳过: {}", stage, t.getTokenAddress().substring(0, 10));
            } catch (Exception e) {
                log.warn("⚠️ FourMeme [{}] 检查 {} 失败: {}", stage, t.getTokenAddress().substring(0, 10), e.getMessage());
                try {
                    markFourMemeStageTime(t, stage, LocalDateTime.now());
                    fourMemeRepo.save(t);
                } catch (OptimisticLockingFailureException ignored) { conflicts++; }
            }
        }
        log.info("✔ FourMeme [{}] 完成: 存活={} 删除={} 跳过={} 冲突跳过={}", stage, survived, deleted, skipped, conflicts);
    }



    private enum Stage { TEN_MIN, TWENTY_MIN, THIRTY_MIN, FORTY_FIVE_MIN, ONE_HOUR, FOUR_HOUR, TWELVE_HOUR, TWENTY_FOUR_HOUR }

    private void runStage(List<PumpToken> tokens, Stage stage) {
        if (tokens.isEmpty()) return;
        int survived = 0, deleted = 0, skipped = 0, conflicts = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                try { Thread.sleep(SLEEP_MS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
            }
            PumpToken t = tokens.get(i);
            try {
                BigDecimal mc = fetchMarketCap(t.getMint());
                markStageTime(t, stage, LocalDateTime.now());
                boolean aboveMin = mc != null && mc.compareTo(BigDecimal.valueOf(MIN_MARKET_CAP)) >= 0;
                if (aboveMin) {
                    t.setStatus("survived"); t.setCurrentMarketCap(mc);
                    pumpTokenRepo.save(t);
                    if (stage == Stage.TWENTY_FOUR_HOUR) saveSnapshot(t, mc, LocalDateTime.now());
                    survived++;
                    log.info("✅ [{}] 存活: {} mc=${}", stage, t.getSymbol(), mc.toPlainString());
                } else if (stage == Stage.TWENTY_FOUR_HOUR) {
                    pumpTokenRepo.delete(t); deleted++;
                } else {
                    pumpTokenRepo.save(t); skipped++;
                }
            } catch (OptimisticLockingFailureException e) {
                // 版本冲突：其他线程已更新该行，本次跳过，下轮会重新抓到正确版本
                conflicts++;
                log.debug("🔀 [{}] 版本冲突跳过: {}", stage, t.getMint().substring(0, 10));
            } catch (Exception e) {
                log.warn("⚠️ [{}] {} 失败: {}", stage, t.getMint().substring(0, 10), e.getMessage());
                try {
                    markStageTime(t, stage, LocalDateTime.now());
                    pumpTokenRepo.save(t);
                } catch (OptimisticLockingFailureException ignored) { conflicts++; }
            }
        }
        log.info("✔ SOL [{}] 存活={} 删除={} 跳过={} 冲突跳过={}", stage, survived, deleted, skipped, conflicts);
    }



    private void markFourMemeStageTime(com.deanrobin.aios.dashboard.model.FourMemeToken t, Stage stage, LocalDateTime now) {
        switch (stage) {
            case TEN_MIN         -> t.setChecked10mAt(now);
            case TWENTY_MIN      -> t.setChecked20mAt(now);
            case THIRTY_MIN      -> t.setChecked30mAt(now);
            case FORTY_FIVE_MIN  -> t.setChecked45mAt(now);
            case ONE_HOUR        -> t.setChecked1hAt(now);
            case FOUR_HOUR       -> t.setChecked4hAt(now);
            case TWELVE_HOUR     -> t.setChecked12hAt(now);
            case TWENTY_FOUR_HOUR-> t.setLastCheckedAt(now);
        }
    }

    /** 根据阶段设置对应的时间戳字段 */
    private void markStageTime(PumpToken t, Stage stage, LocalDateTime now) {
        switch (stage) {
            case TEN_MIN         -> t.setChecked10mAt(now);
            case TWENTY_MIN      -> t.setChecked20mAt(now);
            case THIRTY_MIN      -> t.setChecked30mAt(now);
            case FORTY_FIVE_MIN  -> t.setChecked45mAt(now);
            case ONE_HOUR        -> t.setChecked1hAt(now);
            case FOUR_HOUR       -> t.setChecked4hAt(now);
            case TWELVE_HOUR     -> t.setChecked12hAt(now);
            case TWENTY_FOUR_HOUR-> t.setLastCheckedAt(now);
        }
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
