package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.service.BinanceSquareAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 币安广场热度飞书汇报 Job。
 *
 * 小时榜：每小时整点（09:00、10:00...）
 * 日榜  ：每天 08:00
 *
 * ⚠️ 不加 @Transactional。
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class BinanceSquareReportJob {

    private final BinanceSquareAlertService alertService;

    /** 每小时整点触发小时榜（北京时间） */
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Shanghai")
    public void hourly() {
        alertService.sendHourlyReport();
    }

    /** 每天 08:00 触发日榜（北京时间） */
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Shanghai")
    public void daily() {
        alertService.sendDailyReport();
    }
}
