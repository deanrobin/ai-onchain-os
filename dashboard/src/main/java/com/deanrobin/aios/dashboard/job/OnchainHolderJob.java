package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.repository.OnchainHolderSnapshotRepository;
import com.deanrobin.aios.dashboard.service.OnchainHolderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 链上持仓监控任务。
 *
 * <ul>
 *   <li>每 60 秒轮询一次所有活跃监控任务</li>
 *   <li>每天 01:00 清理 30 天前的快照</li>
 * </ul>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class OnchainHolderJob {

    private final OnchainHolderService          holderService;
    private final OnchainHolderSnapshotRepository snapshotRepo;

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void checkHolders() {
        try {
            holderService.checkAll();
        } catch (Exception e) {
            log.error("❌ 链上持仓检查异常", e);
        }
    }

    /** 每天 01:00 清理 30 天前的快照，防止表膨胀 */
    @Scheduled(cron = "0 0 1 * * *")
    public void cleanupSnapshots() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        int deleted = snapshotRepo.deleteBySnappedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("🗑️ 链上持仓快照清理 deleted={}", deleted);
        }
    }
}
