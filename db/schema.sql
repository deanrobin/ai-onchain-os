-- ai-onchain-os database schema
-- DB: oc_os  (create with: CREATE DATABASE oc_os DEFAULT CHARACTER SET utf8mb4;)

CREATE TABLE IF NOT EXISTS smart_money_wallet (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    address           VARCHAR(100) NOT NULL,
    chain_index       VARCHAR(10)  NOT NULL,
    label             VARCHAR(200),
    score             DECIMAL(10,4) DEFAULT 0,
    win_rate          DECIMAL(10,4) DEFAULT 0,   -- e.g. 0.7500 = 75% (OKX raw /10000)
    realized_pnl_usd  DECIMAL(20,4) DEFAULT 0,
    buy_tx_count      INT           DEFAULT 0,
    sell_tx_count     INT           DEFAULT 0,
    avg_buy_value_usd DECIMAL(20,4) DEFAULT 0,
    source            VARCHAR(50)   DEFAULT 'signal', -- signal / manual
    last_analyzed_at  DATETIME,
    created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_addr_chain (address, chain_index)
);

CREATE TABLE IF NOT EXISTS smart_money_signal (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    chain_index         VARCHAR(10)  NOT NULL,
    token_address       VARCHAR(100) NOT NULL,
    token_symbol        VARCHAR(50),
    token_name          VARCHAR(200),
    token_logo          VARCHAR(500),
    wallet_type         VARCHAR(30),               -- SMART_MONEY / WHALE / INFLUENCER
    trigger_wallet_count INT          DEFAULT 0,
    trigger_wallets     TEXT,                      -- comma-separated addresses
    amount_usd          DECIMAL(20,4) DEFAULT 0,
    price_at_signal     DECIMAL(30,12) DEFAULT 0,
    market_cap_usd      DECIMAL(20,4),
    sold_ratio_percent  DECIMAL(6,4),
    signal_time         DATETIME     NOT NULL,
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_signal_time (signal_time),
    INDEX idx_chain_token (chain_index, token_address)
);

CREATE TABLE IF NOT EXISTS my_address (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    address     VARCHAR(100) NOT NULL,
    chain_index VARCHAR(10)  NOT NULL,
    label       VARCHAR(100),
    is_active   TINYINT(1)   DEFAULT 1,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_addr_chain (address, chain_index)
);

-- 示例：插入 Dean 的地址（替换为真实地址）
-- INSERT INTO my_address (address, chain_index, label)
-- VALUES ('0xYourAddress', '1', 'Main ETH Wallet');

-- 转账白名单表（仅地址维度，无 chain）
-- BSC 地址存储为小写；SOL 地址原样存储
CREATE TABLE IF NOT EXISTS transfer_whitelist (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    address    VARCHAR(100) NOT NULL COMMENT '白名单地址（BSC 小写；SOL 原始大小写）',
    note       VARCHAR(200)          COMMENT '备注（可选）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_address (address)
);

-- ── Perps 永续合约品种表 ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS perp_instrument (
    id                        BIGINT PRIMARY KEY AUTO_INCREMENT,
    exchange                  VARCHAR(20)   NOT NULL COMMENT 'OKX / BINANCE / HYPERLIQUID',
    symbol                    VARCHAR(100)  NOT NULL COMMENT '原始交易对（如 BTC-USDT-SWAP）',
    base_currency             VARCHAR(20)            COMMENT '基础货币',
    quote_currency            VARCHAR(20)            COMMENT '计价货币',
    is_watched                TINYINT(1)    DEFAULT 0 COMMENT '特别关注标记',
    is_active                 TINYINT(1)    DEFAULT 1 COMMENT '是否仍在交易所存在',
    latest_funding_rate       DECIMAL(20,10)         COMMENT '最新资金费率（缓存）',
    latest_funding_updated_at DATETIME               COMMENT '最新费率更新时间',
    first_seen_at             DATETIME      NOT NULL  COMMENT '首次发现时间',
    last_seen_at              DATETIME      NOT NULL  COMMENT '最后同步时间',
    created_at                DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_exchange_symbol (exchange, symbol),
    INDEX idx_exchange_rate (exchange, latest_funding_rate)
);

-- ── QMT 量化行情追踪模块 ────────────────────────────────────────────
-- 详细设计见 docs/QMT.md

-- 品种配置表（4 个固定品种，可扩展）
CREATE TABLE IF NOT EXISTS qmt_symbol (
    id           INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    symbol       VARCHAR(20)  NOT NULL COMMENT 'Binance 交易对，如 BTCUSDT',
    base_asset   VARCHAR(10)  NOT NULL COMMENT '基础资产，如 BTC',
    display_name VARCHAR(10)  NOT NULL COMMENT '页面展示名称',
    is_active    TINYINT(1)   NOT NULL DEFAULT 1,
    sort_order   INT          NOT NULL DEFAULT 0 COMMENT '页面排序',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol (symbol)
) COMMENT='QMT 监控品种';

INSERT IGNORE INTO qmt_symbol (symbol, base_asset, display_name, sort_order) VALUES
('BTCUSDT',  'BTC',  'BTC',  1),
('ETHUSDT',  'ETH',  'ETH',  2),
('PAXGUSDT', 'PAXG', 'PAXG', 3),
('XAUTUSDT', 'XAUT', 'XAUT', 4);

-- K 线数据表（OHLCV，按 symbol + interval_code + open_time 唯一）
CREATE TABLE IF NOT EXISTS qmt_kline (
    id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    symbol         VARCHAR(20)    NOT NULL COMMENT 'Binance 交易对',
    interval_code  VARCHAR(5)     NOT NULL COMMENT '周期：15m/1h/4h/12h/1d/1w',
    open_time      DATETIME(3)    NOT NULL COMMENT 'K 线开盘时间（毫秒精度）',
    open_price     DECIMAL(24,8)  NOT NULL COMMENT '开盘价',
    high_price     DECIMAL(24,8)  NOT NULL COMMENT '最高价',
    low_price      DECIMAL(24,8)  NOT NULL COMMENT '最低价',
    close_price    DECIMAL(24,8)  NOT NULL COMMENT '收盘价',
    volume         DECIMAL(32,8)  NOT NULL COMMENT '基础资产成交量',
    quote_volume   DECIMAL(32,8)  NOT NULL COMMENT 'USDT 成交额',
    trades         INT UNSIGNED   NOT NULL DEFAULT 0 COMMENT '成交笔数',
    taker_buy_vol  DECIMAL(32,8)  NOT NULL DEFAULT 0 COMMENT '主动买入量（买压）',
    close_time     DATETIME(3)    NOT NULL COMMENT 'K 线收盘时间',
    is_closed      TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '0=未收盘 1=已收盘',
    fetched_at     DATETIME       NOT NULL COMMENT '最后抓取时间',
    UNIQUE KEY uk_kline (symbol, interval_code, open_time),
    INDEX idx_sym_ivl_time (symbol, interval_code, open_time DESC),
    INDEX idx_close_time   (close_time)
) COMMENT='QMT K 线数据';

CREATE TABLE IF NOT EXISTS perp_funding_rate (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    exchange          VARCHAR(20)   NOT NULL,
    symbol            VARCHAR(100)  NOT NULL,
    funding_rate      DECIMAL(20,10),
    next_funding_time DATETIME,
    fetched_at        DATETIME      NOT NULL,
    INDEX idx_exchange_symbol (exchange, symbol),
    INDEX idx_fetched_at (fetched_at)
);
