-- ════════════════════════════════════════════════════════════════
-- 20260425-001  crypto-qmt 初始化:BTC 15m K 线 + 技术指标
-- ════════════════════════════════════════════════════════════════

CREATE DATABASE IF NOT EXISTS btc_kline_os
  DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

USE btc_kline_os;

CREATE TABLE IF NOT EXISTS btc_kline_15m (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    open_time    DATETIME      NOT NULL                  COMMENT 'K 线开盘时间(上海时区)',
    open_price   DECIMAL(20,8) NOT NULL                  COMMENT '开盘价',
    high_price   DECIMAL(20,8) NOT NULL                  COMMENT '最高价',
    low_price    DECIMAL(20,8) NOT NULL                  COMMENT '最低价',
    close_price  DECIMAL(20,8) NOT NULL                  COMMENT '收盘价',
    volume       DECIMAL(30,8) NOT NULL                  COMMENT '成交量(BTC)',
    quote_volume DECIMAL(30,4) DEFAULT NULL              COMMENT '成交额(USDT)',
    trade_count  INT           DEFAULT NULL              COMMENT '成交笔数',
    ma20         DECIMAL(20,8) DEFAULT NULL              COMMENT 'MA20 收盘均线',
    ma120        DECIMAL(20,8) DEFAULT NULL              COMMENT 'MA120 收盘均线',
    macd_dif     DECIMAL(20,8) DEFAULT NULL              COMMENT 'MACD DIF (EMA12-EMA26)',
    macd_dea     DECIMAL(20,8) DEFAULT NULL              COMMENT 'MACD DEA (DIF EMA9)',
    macd_hist    DECIMAL(20,8) DEFAULT NULL              COMMENT 'MACD HIST = (DIF-DEA)*2',
    rsi21        DECIMAL(10,4) DEFAULT NULL              COMMENT 'RSI21 (Wilder 平滑)',
    source       VARCHAR(20)   DEFAULT 'binance'         COMMENT '数据来源',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_open_time (open_time),
    KEY idx_open_time_desc (open_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='BTCUSDT 15m K线 + MA/MACD/RSI 历史';
