package com.deanrobin.aios.dashboard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

/**
 * 启动完成后打印 JVM 启动耗时 + 版本信息。
 */
@Service
public class StartupBannerService {

    private static final Logger log = LoggerFactory.getLogger(StartupBannerService.class);

    // 版本号在这里维护，每次发版更新
    private static final String VERSION = "0.1.0";

    @Order(1)   // 优先于 KeyLoaderService 执行
    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        long uptimeMs = rt.getUptime();          // JVM 已运行毫秒
        double uptimeSec = uptimeMs / 1000.0;

        String startCost = uptimeSec < 60
                ? String.format("%.2fs", uptimeSec)
                : String.format("%.0fm%.0fs", uptimeSec / 60, uptimeSec % 60);

        log.info("🚀 aios-dashboard v{} started successfully in {} | port=9900", VERSION, startCost);
    }
}
