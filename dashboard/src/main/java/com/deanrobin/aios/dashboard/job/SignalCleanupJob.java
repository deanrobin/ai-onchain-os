package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.repository.PumpMarketCapSnapshotRepository;
import com.deanrobin.aios.dashboard.repository.SmartMoneySignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 每天 01:30 清理两张无过期机制的表，防止数据无限增长导致慢查询。
 *
 * <ul>
 *   <li>smart_money_signal   — 保留最近 30 天（信号是时效性数据，旧信号无价值）</li>
 *   <li>pump_market_cap_snapshot — 保留最近 30 天（快照仅用于 survivors 历史查看）</li>
 * </ul>
 *
 * 均分批删除（LIMIT 500），防止长事务锁表。
 * ⚠️ 不加 @Transactional（符合 CLAUDE.md 约定）
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class SignalCleanupJob {

    private final SmartMoneySignalRepository    signalRepo;
    private final PumpMarketCapSnapshotRepository snapshotRepo;

    @Scheduled(cron = "0 30 1 * * *", zone = "Asia/Shanghai")
    public void clean() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);

        // ── smart_money_signal（30天前）──────────────────────────────
        int signalTotal = 0, deleted;
        do {
            deleted     = signalRepo.deleteOlderThan(cutoff);
            signalTotal += deleted;
        } while (deleted == 500);

        // ── pump_market_cap_snapshot（30天前）────────────────────────
        int snapTotal = 0;
        do {
            deleted   = snapshotRepo.deleteOlderThan(cutoff);
            snapTotal += deleted;
        } while (deleted == 500);

        if (signalTotal + snapTotal > 0) {
            log.info("🧹 SignalCleanupJob 完成 | signal={} snapshot={} (30天前数据)",
                    signalTotal, snapTotal);
        } else {
            log.debug("🧹 SignalCleanupJob 无需清理");
        }
    }
}
