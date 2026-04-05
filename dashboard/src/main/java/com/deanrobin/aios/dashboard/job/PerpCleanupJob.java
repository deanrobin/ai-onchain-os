package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.repository.PerpFundingRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 每天 00:30 清理 5 天前的 perp_funding_rate 快照数据。
 * 每次最多删 500 条（防止长事务锁表），循环直到全部清理完。
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class PerpCleanupJob {

    private final PerpFundingRateRepository fundingRateRepo;

    @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Shanghai")
    public void cleanOldSnapshots() {
        LocalDateTime before = LocalDateTime.now().minusDays(5);
        int total = 0;
        int deleted;
        do {
            deleted = fundingRateRepo.deleteOldSnapshots(before);
            total  += deleted;
        } while (deleted == 500);   // 若恰好删满 500 条，可能还有更多

        if (total > 0) {
            log.info("🧹 Perps 快照清理完成，共删除 {} 条（5天前数据）", total);
        }
    }
}
