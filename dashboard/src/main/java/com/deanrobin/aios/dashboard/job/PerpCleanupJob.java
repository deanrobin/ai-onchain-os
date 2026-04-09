package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.repository.PerpFundingRateRepository;
import com.deanrobin.aios.dashboard.repository.PerpOpenInterestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 每天 00:30 清理旧快照数据：
 * - perp_funding_rate：保留 5 天
 * - perp_open_interest：保留 7 天
 * 每次最多删 1000 条（防止长事务锁表），循环直到全部清理完。
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class PerpCleanupJob {

    private final PerpFundingRateRepository  fundingRateRepo;
    private final PerpOpenInterestRepository oiRepo;

    @Scheduled(cron = "0 35 0 * * *", zone = "Asia/Shanghai")
    public void cleanOldSnapshots() {
        // ── 资金费率：保留 5 天 ──
        LocalDateTime frBefore = LocalDateTime.now().minusDays(5);
        int frTotal = 0, frDeleted;
        do {
            frDeleted = fundingRateRepo.deleteOldSnapshots(frBefore);
            frTotal  += frDeleted;
        } while (frDeleted == 500);
        if (frTotal > 0) log.info("🧹 资金费率快照清理 {} 条（5天前）", frTotal);

        // ── 持仓量：保留 7 天 ──
        LocalDateTime oiBefore = LocalDateTime.now().minusDays(7);
        int oiTotal = 0, oiDeleted;
        do {
            oiDeleted = oiRepo.deleteOldSnapshots(oiBefore);
            oiTotal  += oiDeleted;
        } while (oiDeleted == 1000);
        if (oiTotal > 0) log.info("🧹 持仓量快照清理 {} 条（7天前）", oiTotal);
    }
}
