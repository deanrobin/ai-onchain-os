package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.repository.PerpFundingRateRepository;
import com.deanrobin.aios.dashboard.repository.PerpOpenInterestRepository;
import com.deanrobin.aios.dashboard.repository.PerpOiWatchSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Perps 数据清理 Job，两个定时任务：
 *
 * 00:35 清理旧快照：
 *   - perp_funding_rate    → 保留 2 天（写入量大：~650条/5min，2天≈374K行；spike检测窗口最长1h，2天足够）
 *   - perp_open_interest   → 保留 7 天
 *
 * 01:15 清理特别关注快照：
 *   - perp_oi_watch_snapshot → 保留 7 天
 *
 * 每次最多删 1000 条（防止长事务锁表），循环直到全部清理完。
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class PerpCleanupJob {

    private final PerpFundingRateRepository      fundingRateRepo;
    private final PerpOpenInterestRepository     oiRepo;
    private final PerpOiWatchSnapshotRepository  watchSnapRepo;

    /** 00:35 清理资金费率 + OI 快照 */
    @Scheduled(cron = "0 35 0 * * *", zone = "Asia/Shanghai")
    public void cleanOldSnapshots() {
        // ── 资金费率：保留 2 天（原 5 天，写入量大缩短以控制表行数）──
        LocalDateTime frBefore = LocalDateTime.now().minusDays(2);
        int frTotal = 0, frDeleted;
        do {
            frDeleted = fundingRateRepo.deleteOldSnapshots(frBefore);
            frTotal  += frDeleted;
        } while (frDeleted == 500);
        if (frTotal > 0) log.info("🧹 资金费率快照清理 {} 条（2天前）", frTotal);

        // ── 持仓量：保留 7 天 ──
        LocalDateTime oiBefore = LocalDateTime.now().minusDays(7);
        int oiTotal = 0, oiDeleted;
        do {
            oiDeleted = oiRepo.deleteOldSnapshots(oiBefore);
            oiTotal  += oiDeleted;
        } while (oiDeleted == 1000);
        if (oiTotal > 0) log.info("🧹 持仓量快照清理 {} 条（7天前）", oiTotal);
    }

    /** 01:15 清理特别关注 5min 快照（7天前数据） */
    @Scheduled(cron = "0 15 1 * * *", zone = "Asia/Shanghai")
    public void cleanWatchSnapshots() {
        LocalDateTime before = LocalDateTime.now().minusDays(7);
        int total = 0, deleted;
        do {
            deleted = watchSnapRepo.deleteOldSnapshots(before);
            total  += deleted;
        } while (deleted == 1000);
        if (total > 0) log.info("🧹 特别关注快照清理 {} 条（7天前）", total);
    }
}
