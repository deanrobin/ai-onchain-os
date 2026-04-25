package com.deanrobin.cryptoqmt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * BTC 15m K 线历史导入器配置(btc-kline.loader.*)
 *
 * 用法:
 *   1) 默认 enabled=false,不会自动跑
 *   2) 填写 start-time / end-time 后设 enabled=true,重启即可一次性拉取
 *   3) 去重:默认从 DB 里最新一根 15m K 线之后开始增量拉;force-reload=true 时忽略
 */
@Data
@Component
@ConfigurationProperties(prefix = "btc-kline.loader")
public class BtcKlineLoaderConfig {

    /** 是否在应用启动时触发导入 */
    private boolean enabled = false;

    /** 起始时间(上海时区 ISO,例:2024-01-01T00:00:00) */
    private String startTime;

    /** 结束时间(上海时区 ISO,例:2026-04-25T00:00:00) */
    private String endTime;

    /** true = 忽略 DB 已有 max(openTime),从 start-time 重新拉 */
    private boolean forceReload = false;

    /** 每批拉取后间隔(毫秒),避免 Binance 限流 */
    private int rateLimitMs = 300;
}
