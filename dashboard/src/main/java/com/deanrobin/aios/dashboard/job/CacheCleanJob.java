package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.repository.WalletTxCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 🧹 每天凌晨 3 点清理 7 天前的 tx_cache，防止表膨胀
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class CacheCleanJob {

    private final WalletTxCacheRepository txCacheRepo;

    @Scheduled(cron = "0 0 3 * * ?", zone = "Asia/Shanghai")
    public void clean() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        int deleted = txCacheRepo.deleteOlderThan(cutoff);
        log.info("🧹 CacheCleanJob 清理 tx_cache {} 条（7天前）", deleted);
    }
}
