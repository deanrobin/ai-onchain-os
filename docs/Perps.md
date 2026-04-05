# Perps — 永续合约资金费率监控

## 功能概述

监控三大交易所（OKX / Binance / Hyperliquid）所有永续合约交易对的资金费率，展示 Top10 最高 / Top10 最低费率排行，支持特别关注标记与新币报警。

---

## 页面功能

- 三个子标签：**OKX** | **Binance** | **Hyperliquid**
- 每个标签展示：
  - 资金费率从大到小 Top 10
  - 资金费率从小到大 Top 10
- ⭐ 标注特别关注交易对
- 每 **1 分钟**自动刷新页面数据

---

## 数据库表

### `perp_instrument` — 合约品种表
| 字段 | 类型 | 说明 |
|------|------|------|
| exchange | VARCHAR(20) | OKX / BINANCE / HYPERLIQUID |
| symbol | VARCHAR(100) | 原始交易对代码（如 BTC-USDT-SWAP） |
| base_currency | VARCHAR(20) | 基础货币 |
| quote_currency | VARCHAR(20) | 计价货币 |
| is_watched | TINYINT(1) | 是否特别关注（1=是） |
| is_active | TINYINT(1) | 是否仍存在于交易所（1=是） |
| latest_funding_rate | DECIMAL(20,10) | 最新资金费率（缓存，加速页面查询） |
| latest_funding_updated_at | DATETIME | 最新费率更新时间 |
| first_seen_at | DATETIME | 首次发现时间 |
| last_seen_at | DATETIME | 最后一次同步时间 |

### `perp_funding_rate` — 资金费率快照表
| 字段 | 类型 | 说明 |
|------|------|------|
| exchange | VARCHAR(20) | 交易所 |
| symbol | VARCHAR(100) | 交易对 |
| funding_rate | DECIMAL(20,10) | 资金费率 |
| next_funding_time | DATETIME | 下次结算时间 |
| fetched_at | DATETIME | 抓取时间 |

---

## 定时任务

| 任务类 | 执行频率 | 说明 |
|--------|---------|------|
| `PerpInstrumentSyncJob` | 每 5 分钟 | 同步三所所有永续合约品种；新增品种触发 HTTP 报警 |
| `PerpFundingRateJob` | 每 10 分钟（全部）/ 每 1 分钟（特别关注） | 抓取资金费率写入快照表并更新品种表缓存 |
| `PerpCleanupJob` | 每天 00:30 | 清理 5 天前的资金费率快照数据 |

---

## API 端点

### 公开端点（无需鉴权）

| 交易所 | 用途 | 端点 |
|--------|------|------|
| OKX | 品种列表 | `GET https://www.okx.com/api/v5/public/instruments?instType=SWAP` |
| OKX | 资金费率 | `GET https://www.okx.com/api/v5/public/funding-rate?instId={instId}` （单个，限速 10req/2s，间隔 1.2s）|
| Binance | 品种列表 | `GET https://fapi.binance.com/fapi/v1/exchangeInfo` |
| Binance | 资金费率 | `GET https://fapi.binance.com/fapi/v1/premiumIndex` （批量，一次返回全部）|
| Hyperliquid | 品种 + 费率 | `POST https://api.hyperliquid.xyz/info` body: `{"type":"metaAndAssetCtxs"}` |

### 后端 REST API

| 端点 | 说明 |
|------|------|
| `GET /api/perps/rates?exchange=OKX` | 返回指定交易所最新费率 Top10 高 / Top10 低 |

---

## 报警配置

- 环境变量：`PERP_ALERT_URL`
- 触发条件：发现新的永续合约品种
- 方式：HTTP GET 请求，参数 `exchange=XX&symbol=XX&count=N`

---

## 特别关注

通过 SQL 直接更新 `is_watched = 1` 标记：

```sql
UPDATE perp_instrument SET is_watched = 1 WHERE exchange = 'OKX' AND symbol = 'BTC-USDT-SWAP';
```

特别关注的交易对资金费率更新频率为 **1 分钟/次**（其他为 10 分钟/次）。

---

## 数据量估算

- 三所合约品种总数：约 1000~2000 个
- 每 10 分钟全量快照：约 2000 条/次
- 每天快照数：约 2000 × 144 = **约 28 万条/天**（全量）
- 保留最近 5 天，按日清理（00:30 cron）
