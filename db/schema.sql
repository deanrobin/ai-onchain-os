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

-- ── Perps 资金费率快照表 ──────────────────────────────────────────
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

-- ── 价格实时表（补充 24h 交易量）────────────────────────────────────
-- 已有 price_ticker 表，补充 volume_24h 字段（如未执行过则运行）
ALTER TABLE price_ticker
    ADD COLUMN IF NOT EXISTS volume_24h DECIMAL(30, 4) COMMENT '24h 交易量（计价货币 USDT）';

-- ── K 线历史表 ──────────────────────────────────────────────────────
-- 存储 BTC/ETH/BNB/SOL 各时间周期的 K 线数据，供均线和策略计算使用
-- 清理策略（00:20 执行）：
--   15m → 保留 7 天；1H → 30 天；4H → 180 天；1D → 730 天；1W → 2000 天
CREATE TABLE IF NOT EXISTS kline_bar (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol       VARCHAR(20)   NOT NULL COMMENT 'BTC / ETH / BNB / SOL',
    bar          VARCHAR(10)   NOT NULL COMMENT '15m / 1H / 4H / 1D / 1W',
    open_time    DATETIME      NOT NULL COMMENT 'K 线开盘时间',
    open_price   DECIMAL(30,8) NOT NULL,
    high_price   DECIMAL(30,8) NOT NULL,
    low_price    DECIMAL(30,8) NOT NULL,
    close_price  DECIMAL(30,8) NOT NULL,
    volume       DECIMAL(30,4) NOT NULL COMMENT '成交量（基础货币）',
    volume_usdt  DECIMAL(30,4)          COMMENT '成交量（USDT）',
    confirmed    TINYINT(1)    DEFAULT 0 COMMENT '0=未收盘 1=已收盘',
    created_at   DATETIME      DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol_bar_time (symbol, bar, open_time),
    INDEX idx_symbol_bar (symbol, bar),
    INDEX idx_open_time  (open_time)
);

-- ── 链上持仓监控任务表 ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS onchain_watch (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_name       VARCHAR(50)   NOT NULL COMMENT '代币名称，如 STO',
    contract_addr    VARCHAR(42)   NOT NULL COMMENT '合约地址 0x...',
    network          VARCHAR(10)   NOT NULL COMMENT 'ETH | BSC',
    token_decimals   TINYINT       NOT NULL DEFAULT 18 COMMENT '代币精度',
    threshold_mode   VARCHAR(10)   NOT NULL DEFAULT 'USD' COMMENT 'TOKEN | USD',
    threshold_amount DECIMAL(30,4) COMMENT '代币数量阈值（TOKEN 模式）',
    threshold_usd    DECIMAL(20,2) NOT NULL DEFAULT 50000 COMMENT 'USD 阈值（默认 50000）',
    watched_addrs    JSON          NOT NULL COMMENT '["0xABC","0xDEF"]',
    is_active        TINYINT(1)    NOT NULL DEFAULT 1,
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_network (network),
    INDEX idx_active  (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 链上持仓余额快照表 ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS onchain_holder_snapshot (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    watch_id       BIGINT        NOT NULL COMMENT 'FK -> onchain_watch.id',
    wallet_addr    VARCHAR(42)   NOT NULL COMMENT '钱包地址',
    balance_raw    DECIMAL(40,0) NOT NULL COMMENT '原始余额（未除精度）',
    balance_token  DECIMAL(30,6) NOT NULL COMMENT '余额（除精度后）',
    price_usd      DECIMAL(20,8) COMMENT '快照时代币 USD 价格',
    value_usd      DECIMAL(20,2) COMMENT '折算 USD 市值',
    block_number   BIGINT        NOT NULL COMMENT '取余额时的区块高度',
    snapped_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_watch_wallet (watch_id, wallet_addr),
    INDEX idx_snapped_at   (snapped_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ════════════════════════════════════════════════════════════════
-- 20260408-001  合约行情快照 + 成交量报警黑名单（含默认主流币）
-- ════════════════════════════════════════════════════════════════

-- ── Binance 合约行情快照表（U本位永续，每分钟更新）─────────────────────
CREATE TABLE IF NOT EXISTS binance_ticker (
    id                BIGINT        AUTO_INCREMENT PRIMARY KEY,
    symbol            VARCHAR(50)   NOT NULL COMMENT '交易对，如 BTCUSDT',
    base_currency     VARCHAR(20)            COMMENT '基础货币，如 BTC',
    last_price        DECIMAL(30,10) NOT NULL COMMENT '最新成交价',
    price_change_pct  DECIMAL(12,4)  NOT NULL COMMENT '24h涨跌幅%',
    quote_volume      DECIMAL(30,4)  NOT NULL COMMENT '24h成交额(USDT)',
    trade_count       INT                    COMMENT '24h成交笔数',
    fetched_at        DATETIME       NOT NULL COMMENT '数据抓取时间',
    UNIQUE KEY uk_symbol (symbol),
    INDEX idx_quote_volume (quote_volume),
    INDEX idx_price_change (price_change_pct)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 合约成交量报警黑名单（命中则不报警）────────────────────────────────
CREATE TABLE IF NOT EXISTS ticker_alert_blacklist (
    id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
    symbol     VARCHAR(50)  NOT NULL COMMENT '合约交易对，如 BTCUSDT',
    note       VARCHAR(200)          COMMENT '备注',
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO ticker_alert_blacklist (symbol, note) VALUES
    ('BTCUSDT',  '主流币，24h量常态超阈值'),
    ('ETHUSDT',  '主流币，24h量常态超阈值'),
    ('BNBUSDT',  '主流币，24h量常态超阈值'),
    ('SOLUSDT',  '主流币，24h量常态超阈值'),
    ('XRPUSDT',  '主流币，24h量常态超阈值'),
    ('DOGEUSDT', '主流币，24h量常态超阈值');

-- ════════════════════════════════════════════════════════════════
-- 20260408-002  合约供应量快照（CoinGecko）+ OI持仓量快照
-- ════════════════════════════════════════════════════════════════

-- ── 合约代币供应量快照表（CoinGecko，12H 刷新）────────────────────────
-- 用于市值计算：市值=价格×总量，流通市值=价格×流通量
CREATE TABLE IF NOT EXISTS perp_supply_snapshot (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    base_currency       VARCHAR(20)   NOT NULL COMMENT '基础货币，如 BTC/ETH/SOL',
    coingecko_id        VARCHAR(100)           COMMENT 'CoinGecko coin ID',
    circulating_supply  DECIMAL(40,4)          COMMENT '流通量',
    total_supply        DECIMAL(40,4)          COMMENT '总量（可能为NULL）',
    max_supply          DECIMAL(40,4)          COMMENT '最大供应量',
    fetched_at          DATETIME      NOT NULL  COMMENT '数据获取时间',
    created_at          DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_base_currency (base_currency),
    INDEX idx_fetched_at (fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 合约持仓量(OI)快照表 ─────────────────────────────────────────────
-- 每5分钟快照一次，用于15分钟/4小时持仓变化对比
-- 清理策略：保留 7 天
CREATE TABLE IF NOT EXISTS perp_open_interest (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange    VARCHAR(20)    NOT NULL COMMENT 'OKX / BINANCE',
    symbol      VARCHAR(100)   NOT NULL COMMENT '合约代码，如 BTCUSDT',
    oi_coin     DECIMAL(30,4)           COMMENT '持仓量（基础货币，如 BTC 数量）',
    oi_usdt     DECIMAL(30,4)           COMMENT '持仓价值（USDT，= oi_coin × price）',
    fetched_at  DATETIME       NOT NULL COMMENT '快照时间',
    INDEX idx_exchange_symbol_time (exchange, symbol, fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
