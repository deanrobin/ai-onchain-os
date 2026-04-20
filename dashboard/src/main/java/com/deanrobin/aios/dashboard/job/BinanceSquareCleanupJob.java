package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.repository.BinanceSquarePostRepository;
import com.deanrobin.aios.dashboard.repository.BinanceSquareTokenStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 每天 02:30 北京时间清理 7 天前的币安广场数据（帖子 + 代币统计）。
 *
 * 每次删除上限 500 行，循环直到清完，避免大事务锁表。
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class BinanceSquareCleanupJob {

    private final BinanceSquarePostRepository      postRepo;
    private final BinanceSquareTokenStatRepository statRepo;

    @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Shanghai")
    public void clean() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        int posts = 0, stats = 0, r;
        while ((r = postRepo.deleteOlderThan(cutoff)) > 0) posts += r;
        while ((r = statRepo.deleteOlderThan(cutoff)) > 0) stats += r;
        log.info("🧹 币安广场清理完成（>7天）帖子={} 代币统计={}", posts, stats);
    }
}
