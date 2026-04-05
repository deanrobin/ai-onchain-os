# QMT — 量化行情追踪模块

> Quant Market Tracker：追踪 BTC / ETH / PAXG / XAUT 四个标的的多周期 K 线，
> 提供技术指标与跨周期趋势矩阵，辅助判断多空动量。

---

## 1. 支持品种

| 标的  | Binance 交易对 | 类型          | 说明                       |
|-------|---------------|---------------|----------------------------|
| BTC   | BTCUSDT       | 主流加密货币  | 市场基准                   |
| ETH   | ETHUSDT       | 主流加密货币  | 智能合约平台基准           |
| PAXG  | PAXGUSDT      | 黄金锚定代币  | Paxos Gold，1 PAXG ≈ 1 金盎司 |
| XAUT  | XAUTUSDT      | 黄金锚定代币  | Tether Gold，1 XAUT ≈ 1 金盎司 |

> 设计意图：BTC/ETH 代表风险资产，PAXG/XAUT 代表黄金避险资产，
> 四者放在同一看板便于观察风险情绪切换。

---

## 2. 时间周期

| 用户标识 | Binance interval | 保留 K 线条数 | 覆盖时长 |
|---------|-----------------|-------------|---------|
| 15m     | `15m`           | 200         | ~50 小时 |
| 1h      | `1h`            | 200         | ~8 天   |
| 4h      | `4h`            | 200         | ~33 天  |
| 12h     | `12h`           | 200         | ~100 天 |
| 1d      | `1d`            | 365         | ~1 年   |
| 7d      | `1w`            | 104         | ~2 年   |

> `7d` 在 Binance API 对应 `1w`（周线），字段 `interval_code` 统一存储为
> Binance 原始值（`15m / 1h / 4h / 12h / 1d / 1w`）。

---

## 3. 数据源

```
Binance 现货公开 API（无需鉴权）
GET https://api.binance.com/api/v3/klines
  ?symbol=BTCUSDT
  &interval=1h
  &limit=200
```

返回格式（每条 K 线为数组）：

```
[
  open_time,          // 0  开盘时间戳 ms
  open,               // 1  开盘价
  high,               // 2  最高价
  low,                // 3  最低价
  close,              // 4  收盘价
  volume,             // 5  基础资产成交量
  close_time,         // 6  收盘时间戳 ms
  quote_volume,       // 7  USDT 成交额
  trades,             // 8  成交笔数
  taker_buy_volume,   // 9  主动买入基础资产量
  taker_buy_quote,    // 10 主动买入 USDT 额
  ignore              // 11 废弃字段
]
```

---

## 4. 采集策略

### 触发方式

`QmtKlineFetchJob` —— 单 Job 覆盖所有品种 × 周期：

| 类型   | 调度                            | 说明                        |
|--------|---------------------------------|-----------------------------|
| 全量初始化 | 启动后 10s（initialDelay）    | 首次拉取各周期完整历史      |
| 增量刷新   | 每 **5 min**（fixedDelay）    | 拉最新 3 条（覆盖未收盘 K 线）|

> 每 5 min 刷新足以覆盖所有周期的最新数据，不需要针对不同周期分别调度。
> 增量拉 `limit=3` 而非全量，避免重复写入。

### UPSERT 策略

以 `(symbol, interval_code, open_time)` 为唯一键：

- **新 K 线**：INSERT
- **当前未关闭 K 线**（`close_time > now`）：UPDATE（价格会变动）
- **已关闭 K 线**：幂等，重复写入不影响结果

### 清理策略

`QmtCleanupJob`，每天凌晨 3:00 执行，按周期保留上限删除超量旧数据：

```sql
DELETE FROM qmt_kline
WHERE symbol = ? AND interval_code = ?
  AND open_time < (
    SELECT open_time FROM qmt_kline
    WHERE symbol = ? AND interval_code = ?
    ORDER BY open_time DESC
    LIMIT 1 OFFSET {retain_count}
  )
```

---

## 5. 技术指标

以下指标**在 Service 层实时计算**，不存入 DB，避免冗余数据：

| 指标          | 计算方式                        | 参考周期     |
|---------------|---------------------------------|-------------|
| MA7 / MA20 / MA50 | 简单移动平均（close）        | 所有周期     |
| RSI-14        | Wilder 平滑法                   | 1h / 4h / 1d |
| 价格变动率     | (close_t - close_t-n) / close_t-n | 各周期对比   |
| 振幅           | (high - low) / open × 100%     | 各周期       |
| 成交量倍数     | volume / MA20(volume)           | 量价背离判断 |
| 黄金/BTC 比价  | PAXG close / BTC close          | 避险情绪参考 |

---

## 6. DB 设计

### 6.1 qmt_symbol — 品种配置表

```sql
CREATE TABLE IF NOT EXISTS qmt_symbol (
    id          INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    symbol      VARCHAR(20)  NOT NULL COMMENT 'Binance 交易对，如 BTCUSDT',
    base_asset  VARCHAR(10)  NOT NULL COMMENT '基础资产，如 BTC',
    display_name VARCHAR(10) NOT NULL COMMENT '展示名称，如 BTC',
    is_active   TINYINT(1)   NOT NULL DEFAULT 1,
    sort_order  INT          NOT NULL DEFAULT 0 COMMENT '页面排序',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol (symbol)
) COMMENT='QMT 监控品种';

-- 初始数据
INSERT INTO qmt_symbol (symbol, base_asset, display_name, sort_order) VALUES
('BTCUSDT',  'BTC',  'BTC',  1),
('ETHUSDT',  'ETH',  'ETH',  2),
('PAXGUSDT', 'PAXG', 'PAXG', 3),
('XAUTUSDT', 'XAUT', 'XAUT', 4);
```

### 6.2 qmt_kline — K 线数据表

```sql
CREATE TABLE IF NOT EXISTS qmt_kline (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    symbol          VARCHAR(20)    NOT NULL COMMENT 'Binance 交易对',
    interval_code   VARCHAR(5)     NOT NULL COMMENT '周期：15m/1h/4h/12h/1d/1w',
    open_time       DATETIME(3)    NOT NULL COMMENT 'K 线开盘时间（毫秒精度）',
    open_price      DECIMAL(24,8)  NOT NULL COMMENT '开盘价',
    high_price      DECIMAL(24,8)  NOT NULL COMMENT '最高价',
    low_price       DECIMAL(24,8)  NOT NULL COMMENT '最低价',
    close_price     DECIMAL(24,8)  NOT NULL COMMENT '收盘价',
    volume          DECIMAL(32,8)  NOT NULL COMMENT '基础资产成交量',
    quote_volume    DECIMAL(32,8)  NOT NULL COMMENT 'USDT 成交额',
    trades          INT UNSIGNED   NOT NULL DEFAULT 0 COMMENT '成交笔数',
    taker_buy_vol   DECIMAL(32,8)  NOT NULL DEFAULT 0 COMMENT '主动买入基础资产量',
    close_time      DATETIME(3)    NOT NULL COMMENT 'K 线收盘时间',
    is_closed       TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '0=未收盘 1=已收盘',
    fetched_at      DATETIME       NOT NULL COMMENT '最后抓取时间',
    UNIQUE KEY uk_kline (symbol, interval_code, open_time),
    INDEX idx_symbol_interval_time (symbol, interval_code, open_time DESC),
    INDEX idx_close_time (close_time)
) COMMENT='QMT K 线数据（OHLCV）';
```

> **字段说明**
> - `open_time` / `close_time` 使用 `DATETIME(3)` 保留毫秒，与 Binance 时间戳对齐
> - `DECIMAL(24,8)` 覆盖 PAXG/XAUT 等高价资产的精度需求
> - `taker_buy_vol` 用于计算买卖压力（taker buy ratio）

---

## 7. 周期保留配置

```yaml
# application.yml
qmt:
  retain:
    15m: 200
    1h:  200
    4h:  200
    12h: 200
    1d:  365
    1w:  104
```

---

## 8. Job 设计

### QmtKlineFetchJob

```
初始化：startup + 10s，拉 limit=200（各周期完整历史）
增量刷新：fixedDelay=300_000（5 min），拉 limit=3
4 symbol × 6 interval = 24 次 API 调用 / 轮
API 间隔：100ms（Binance 公开接口限速宽松，1200 req/min）
```

### QmtCleanupJob

```
触发：每天 03:10 北京时间
逻辑：按周期配置删除超量历史 K 线
```

---

## 9. API 设计（规划）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/qmt/klines?symbol=BTCUSDT&interval=1h` | 返回指定品种+周期最新 K 线（含计算指标） |
| GET  | `/api/qmt/overview` | 返回 4 品种 × 6 周期的涨跌幅矩阵 |
| GET  | `/api/qmt/latest`   | 返回 4 品种最新价格快照 |

---

## 10. 页面设计（/qmt）

```
┌─────────────────────────────────────────────────────┐
│  📈 QMT 量化行情     [BTC] [ETH] [PAXG] [XAUT]      │
├─────────────────────────────────────────────────────┤
│  多周期涨跌幅矩阵（热力图）                           │
│       15m    1h    4h    12h    1d    7d             │
│  BTC  +0.3%  +1.2%  -0.5%  +2.1%  +5.3%  +12.1%   │
│  ETH  ...                                            │
│  PAXG ...                                            │
│  XAUT ...                                            │
├─────────────────────────────────────────────────────┤
│  当前选中品种详情：K 线图 + 技术指标                  │
│  [周期切换 Tab]                                      │
└─────────────────────────────────────────────────────┘
```

---

## 11. 注意事项

1. **PAXG / XAUT 流动性低**：成交量远小于 BTC/ETH，振幅字段意义有限，
   主要看价格趋势和与黄金现货的比价偏离。
2. **Binance 限速**：公开 K 线接口 1200 req/min，24 次/轮 × 每 5 min = 完全无压力。
3. **周线 K 线时间对齐**：Binance `1w` 以 UTC 周一 00:00 为开盘，展示时注意时区换算。
4. **未收盘 K 线**：当前周期最后一根 `close_time > now`，价格随时更新，
   计算技术指标时需标注"实时"而非"已定格"。
