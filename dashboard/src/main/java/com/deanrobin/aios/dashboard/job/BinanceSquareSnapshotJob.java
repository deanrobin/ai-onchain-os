package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.BinanceSquareRankSnapshot;
import com.deanrobin.aios.dashboard.repository.BinanceSquareRankSnapshotRepository;
import com.deanrobin.aios.dashboard.service.BinanceSquareService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 每 15 分钟对 1h / 24h Top20 各拍一份快照，供网页显示排名升降。
 *
 * 清理由 BinanceSquareCleanupJob 负责（>7 天删除，批次 500）。
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class BinanceSquareSnapshotJob {

    private static final int SNAPSHOT_LIMIT = 20;
    private static final int[] WINDOWS = {1, 24};

    private final BinanceSquareService                squareService;
    private final BinanceSquareRankSnapshotRepository snapshotRepo;

    /** 0/15/30/45 分各拍一次。 */
    @Scheduled(cron = "0 0/15 * * * *", zone = "Asia/Shanghai")
    public void snapshot() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        int totalSaved = 0;
        for (int window : WINDOWS) {
            List<Map<String, Object>> top = squareService.topTokensSince(window, SNAPSHOT_LIMIT);
            if (top.isEmpty()) continue;

            List<BinanceSquareRankSnapshot> rows = new ArrayList<>(top.size());
            for (int i = 0; i < top.size(); i++) {
                Map<String, Object> d = top.get(i);
                BinanceSquareRankSnapshot r = new BinanceSquareRankSnapshot();
                r.setSnapshotAt(now);
                r.setWindowHours(window);
                r.setRankNo(i + 1);
                r.setToken(String.valueOf(d.get("token")));
                r.setScore(((Number) d.getOrDefault("score", 0)).intValue());
                rows.add(r);
            }
            try {
                snapshotRepo.saveAll(rows);
                totalSaved += rows.size();
            } catch (DataIntegrityViolationException e) {
                log.warn("⚠️ 快照写入冲突 window={} 已跳过: {}", window, e.getMessage());
            }
        }
        if (totalSaved > 0) {
            log.info("📸 币安广场榜单快照完成 at={} 行数={}", now, totalSaved);
        }
    }
}
