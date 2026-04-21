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

-- ── OI 突破告警 + 特别关注记录表 ────────────────────────────────────────
-- 当持仓量(OI) USD >= 5000万时触发，48h内同品种不重复告警。
-- watch_until = alerted_at + 48h，作为特别关注截止时间。
-- 48h到期后若 OI 仍 >= 5000万，PerpOiJob 重新创建记录续期。
CREATE TABLE IF NOT EXISTS perp_oi_alert (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    exchange    VARCHAR(20)    NOT NULL COMMENT 'OKX / BINANCE / HYPERLIQUID',
    symbol      VARCHAR(100)   NOT NULL COMMENT '合约代码',
    oi_usd      DECIMAL(30,4)           COMMENT '触发时持仓量 USD',
    alerted_at  DATETIME       NOT NULL COMMENT '告警时间',
    watch_until DATETIME       NOT NULL COMMENT '特别关注截止时间（alerted_at + 48h）',
    INDEX idx_exch_sym (exchange, symbol),
    INDEX idx_watch_until (watch_until),
    INDEX idx_alerted_at (alerted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 特别关注品种 5 分钟快照表 ──────────────────────────────────────────
-- 处于特别关注期的品种每 5 分钟记录一次价格/涨跌/持仓量，供事后复盘。
-- 清理策略：凌晨 01:15 删除 7 天前的数据。
CREATE TABLE IF NOT EXISTS perp_oi_watch_snapshot (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    exchange    VARCHAR(20)    NOT NULL,
    symbol      VARCHAR(100)   NOT NULL,
    price_usd   DECIMAL(30,8)           COMMENT '当前价格（USD）',
    change_24h  DECIMAL(10,4)           COMMENT '24h 涨跌幅（%）',
    oi_usd      DECIMAL(30,4)           COMMENT '持仓量 USD',
    snapped_at  DATETIME       NOT NULL,
    INDEX idx_exch_sym_time (exchange, symbol, snapped_at),
    INDEX idx_snapped_at (snapped_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- 给 perp_instrument 增加 OI 缓存列（已有表 ALTER）
ALTER TABLE perp_instrument
    ADD COLUMN IF NOT EXISTS latest_oi            DECIMAL(30,4) COMMENT '最新持仓量（基础货币；HL 为 USD）',
    ADD COLUMN IF NOT EXISTS latest_oi_usd        DECIMAL(30,4) COMMENT '最新持仓量 USD 估算',
    ADD COLUMN IF NOT EXISTS latest_oi_updated_at DATETIME      COMMENT '持仓量更新时间';

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

-- ── 合约成交量异动持仓快照表 ──────────────────────────────────────
-- 当 1H 合约成交量 > 20期均量×2 时触发，记录 OI / 多空比 / 费率快照，并在 24H / 48H 跟进
CREATE TABLE IF NOT EXISTS perp_volume_snapshot (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol            VARCHAR(20)    NOT NULL COMMENT 'BTC / ETH / BNB / SOL',
    bar               VARCHAR(10)    NOT NULL DEFAULT '1H' COMMENT 'K线周期',
    volume_usdt       DECIMAL(30,4)  NOT NULL COMMENT '触发时合约成交额（USDT）',
    avg_volume_usdt   DECIMAL(30,4)  NOT NULL COMMENT '20期均量（USDT）',
    volume_ratio      DECIMAL(10,4)  NOT NULL COMMENT '成交量倍数（volume/avg）',
    close_price       DECIMAL(30,8)  COMMENT '触发时收盘价（USDT）',
    oi_usdt           DECIMAL(30,4)  COMMENT '持仓量 OI（USDT，Binance）',
    ls_ratio          DECIMAL(10,4)  COMMENT '多空账户比（>1 代表多头更多）',
    long_pct          DECIMAL(10,4)  COMMENT '做多账户占比（0~1）',
    short_pct         DECIMAL(10,4)  COMMENT '做空账户占比（0~1）',
    funding_rate      DECIMAL(20,10) COMMENT '资金费率（Binance）',
    snapped_at        DATETIME       NOT NULL COMMENT '快照时间',
    followup_24h_done TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '24H跟进是否已发送',
    followup_48h_done TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '48H跟进是否已发送',
    INDEX idx_symbol_snapped (symbol, snapped_at),
    INDEX idx_followup       (followup_24h_done, followup_48h_done, snapped_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合约成交量异动持仓快照（OI+多空比+费率）';

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

-- ════════════════════════════════════════════════════════════════
-- 20260411-001  DB 性能优化：补全缺失索引，消除慢查询
-- ════════════════════════════════════════════════════════════════

-- smart_money_signal：findLatest 每 10s 调用 40+ 次，补全复合索引
-- 原有 idx_chain_token(chain_index, token_address) 缺少 wallet_type + signal_time
-- 新索引可覆盖 ORDER BY signal_time DESC LIMIT 1，无需额外排序
ALTER TABLE smart_money_signal
    ADD INDEX IF NOT EXISTS idx_chain_token_wt_time (chain_index, token_address, wallet_type, signal_time);

-- pump_token：8 个阶段查询均含 received_at < :before AND checked_Xm_at IS NULL
-- 无 received_at 索引时全表扫描，表保留 100000 行时压力极大
ALTER TABLE pump_token
    ADD INDEX IF NOT EXISTS idx_received_at (received_at),
    ADD INDEX IF NOT EXISTS idx_status_received (status, received_at);

-- four_meme_token：同上
ALTER TABLE four_meme_token
    ADD INDEX IF NOT EXISTS idx_received_at (received_at),
    ADD INDEX IF NOT EXISTS idx_status_received (status, received_at);

-- pump_market_cap_snapshot：findByMintRecent 每次加载 survivors 都调用
-- 当前无索引，随数据增长全表扫描
ALTER TABLE pump_market_cap_snapshot
    ADD INDEX IF NOT EXISTS idx_mint_checked_at (mint, checked_at);

-- ════════════════════════════════════════════════════════════════
-- 20260412-001  perp_volume_snapshot 新增主动买卖量比字段
-- ════════════════════════════════════════════════════════════════

-- 主动买/卖量比（Binance /futures/data/takerlongshortRatio buySellRatio）
-- buyVol/sellVol > 1 代表主动买入量更大（多头主导）
ALTER TABLE perp_volume_snapshot
    ADD COLUMN IF NOT EXISTS taker_buy_ratio DECIMAL(10,4) COMMENT '主动买/卖量比（>1代表主动买入量更大）'
    AFTER funding_rate;

-- ════════════════════════════════════════════════════════════════
-- 20260420-001  币安广场代币热度追踪（5min 抓取 + 整点/日 飞书汇报 + 7 天清理）
-- ════════════════════════════════════════════════════════════════

-- 帖子原文（去重 + 溯源）。7 天保留，BinanceSquareCleanupJob 02:30 清理。
CREATE TABLE IF NOT EXISTS binance_square_post (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id       VARCHAR(100)  NOT NULL COMMENT '币安广场原始帖子 ID',
    author_name   VARCHAR(200),
    content       TEXT,
    like_count    INT DEFAULT 0,
    comment_count INT DEFAULT 0,
    score         INT DEFAULT 0 COMMENT '点赞+评论',
    tokens        VARCHAR(2000) COMMENT 'JSON 数组，已抽取到的代币',
    post_date     DATETIME      NOT NULL COMMENT '帖子原始时间',
    fetched_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_post_id (post_id),
    INDEX idx_post_date (post_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 一篇帖子 × 多个代币的统计行（聚合时按 post_date 时间窗 SUM(score)）。
CREATE TABLE IF NOT EXISTS binance_square_token_stat (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id     VARCHAR(100) NOT NULL,
    token       VARCHAR(50)  NOT NULL,
    in_content  TINYINT(1)   DEFAULT 0 COMMENT '是否来自正文 $XXX',
    in_fields   TINYINT(1)   DEFAULT 0 COMMENT '是否来自作者标注字段',
    in_binance  TINYINT(1)   DEFAULT 0 COMMENT '是否在币安上架',
    likes       INT DEFAULT 0,
    comments    INT DEFAULT 0,
    score       INT DEFAULT 0,
    post_date   DATETIME     NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_post_token (post_id, token),
    INDEX idx_token_date (token, post_date),
    INDEX idx_post_date (post_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ════════════════════════════════════════════════════════════════
-- 20260420-002  币安广场榜单 15min 快照（用于网页显示排名升降）
-- ════════════════════════════════════════════════════════════════

-- 每 15 分钟对 1h / 24h 榜 Top20 各拍一份快照。7 天保留。
CREATE TABLE IF NOT EXISTS binance_square_rank_snapshot (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_at   DATETIME    NOT NULL COMMENT '快照时间点',
    window_hours  INT         NOT NULL COMMENT '时间窗：1 或 24',
    rank_no       INT         NOT NULL COMMENT '当次排名（1 起）',
    token         VARCHAR(50) NOT NULL,
    score         INT         NOT NULL DEFAULT 0,
    UNIQUE KEY uk_snap_token (snapshot_at, window_hours, token),
    INDEX idx_snap_at (snapshot_at),
    INDEX idx_window_at (window_hours, snapshot_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ════════════════════════════════════════════════════════════════
-- 20260420-003  BTC-USDT 15 分钟 K 线历史 + 策略做多信号
-- 目的：录入近 3 年 Binance BTCUSDT 15m K 线 + MA20/MA120/MACD/RSI，
--       训练后基于历史特征识别做多机会，触发时入 signal 表并报警 / 网页显示
-- ════════════════════════════════════════════════════════════════

-- ── BTC 15m K 线 + 技术指标历史表 ──────────────────────────────────
-- 数据源：Binance Spot（BTCUSDT，15m），open_time 为 K 线开盘时间
-- 预计规模：3 年 × 365 × 24 × 4 ≈ 10.5 万行，bulk import 友好
-- 指标约定：
--   ma20 / ma120        : 收盘价简单移动均线
--   macd_dif / dea / hist: 标准 MACD(12,26,9)，hist = (dif - dea) * 2
--   rsi14               : 14 周期 RSI（已由 20260422-001 重命名为 rsi21）
CREATE TABLE IF NOT EXISTS btc_kline_15m (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    open_time    DATETIME       NOT NULL COMMENT 'K 线开盘时间（UTC+8）',
    open_price   DECIMAL(20,8)  NOT NULL,
    high_price   DECIMAL(20,8)  NOT NULL,
    low_price    DECIMAL(20,8)  NOT NULL,
    close_price  DECIMAL(20,8)  NOT NULL,
    volume       DECIMAL(30,8)  NOT NULL COMMENT '成交量（BTC）',
    quote_volume DECIMAL(30,4)            COMMENT '成交额（USDT）',
    trade_count  INT                      COMMENT '成交笔数',
    ma20         DECIMAL(20,8)            COMMENT 'MA20 收盘均线',
    ma120        DECIMAL(20,8)            COMMENT 'MA120 收盘均线',
    macd_dif     DECIMAL(20,8)            COMMENT 'MACD DIF (EMA12 - EMA26)',
    macd_dea     DECIMAL(20,8)            COMMENT 'MACD DEA (DIF 的 EMA9)',
    macd_hist    DECIMAL(20,8)            COMMENT 'MACD 柱 = (DIF - DEA) * 2',
    rsi14        DECIMAL(10,4)            COMMENT 'RSI14 — 已由 20260422-001 重命名为 rsi21',
    source       VARCHAR(20)    DEFAULT 'binance' COMMENT '数据来源',
    created_at   DATETIME       DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_open_time (open_time),
    INDEX idx_open_time_desc (open_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='BTCUSDT 15m K线 + 技术指标（MA20/MA120/MACD/RSI）';

-- ── BTC 做多策略信号表 ─────────────────────────────────────────────
-- 策略识别出做多机会时写入一条记录，用于：
--   1) 站内飞书报警（pending -> sent）
--   2) 网页实时显示
--   3) 事后跟踪止盈止损命中情况
-- 止盈止损允许多级（take_profit_1/2、stop_loss），按 JSON 扩展
CREATE TABLE IF NOT EXISTS btc_long_signal (
    id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
    signal_time        DATETIME       NOT NULL COMMENT '信号触发时间（对齐 K 线开盘时间）',
    kline_id           BIGINT                  COMMENT 'FK -> btc_kline_15m.id（触发 K 线）',
    strategy_name      VARCHAR(50)    NOT NULL COMMENT '策略标识，如 MA_CROSS_V1 / MACD_GOLDEN_V1',
    strategy_version   VARCHAR(20)    DEFAULT 'v1' COMMENT '策略版本，便于回测对比',
    entry_price        DECIMAL(20,8)  NOT NULL COMMENT '建议开多价格（多为触发 K 线收盘价）',
    take_profit_price  DECIMAL(20,8)           COMMENT '主止盈价',
    stop_loss_price    DECIMAL(20,8)           COMMENT '主止损价',
    take_profit_pct    DECIMAL(10,4)           COMMENT '止盈百分比（相对 entry）',
    stop_loss_pct      DECIMAL(10,4)           COMMENT '止损百分比（相对 entry）',
    risk_reward        DECIMAL(10,4)           COMMENT '盈亏比 = tp_pct / sl_pct',
    confidence         DECIMAL(5,2)            COMMENT '策略置信度 0~100',
    indicators_snapshot JSON                    COMMENT '{ma20, ma120, macd_dif, macd_dea, macd_hist, rsi21, ...}',
    reason             VARCHAR(500)            COMMENT '触发理由文字描述',
    status             VARCHAR(20)    NOT NULL DEFAULT 'OPEN'
                       COMMENT 'OPEN / TP_HIT / SL_HIT / EXPIRED / CANCELLED',
    closed_at          DATETIME                COMMENT '信号关闭时间',
    closed_price       DECIMAL(20,8)           COMMENT '关闭时价格',
    realized_pct       DECIMAL(10,4)           COMMENT '已实现收益率（%）',
    alert_status       VARCHAR(20)    NOT NULL DEFAULT 'PENDING'
                       COMMENT 'PENDING / SENT / FAILED / SKIPPED',
    alert_sent_at      DATETIME                COMMENT '飞书报警发送时间',
    created_at         DATETIME       DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_signal_time   (signal_time),
    INDEX idx_status        (status, signal_time),
    INDEX idx_alert_pending (alert_status, signal_time),
    INDEX idx_strategy      (strategy_name, signal_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='BTC 做多策略信号（触发→报警→跟踪）';

-- ════════════════════════════════════════════════════════════════
-- 20260422-001  btc_kline_15m.rsi14 → rsi21（改用 21 周期 Wilder RSI）
-- ════════════════════════════════════════════════════════════════
-- 对已有表做列重命名；新鲜库跑到 20260420-003 时列名仍是 rsi14，
-- 这个批次将其统一改为 rsi21，与 Java 实体 BtcKline15m.rsi21 对齐。
ALTER TABLE btc_kline_15m
    CHANGE COLUMN rsi14 rsi21 DECIMAL(10,4) COMMENT 'RSI21 (Wilder 平滑)';
