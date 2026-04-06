# 链上持仓监控 (OnchainHolder) — 功能设计文档

> 状态：设计稿 v1.0 | 日期：2026-04-06

---

## 一、功能概述

用户在页面添加"监控任务"（`OnchainWatch`），指定一个 ERC20 代币合约 + 一组钱包地址。
后台 Job 每 60 秒轮询一次链上余额，当余额变化折算 USD 超过阈值，立即通过 **飞书 Webhook** 告警。

> **命名约定**
> - 页面显示：**监控任务**（Watch Task）
> - Java 实体：`OnchainWatch`
> - URL slug：`/onchain-holder`

---

## 二、页面布局

```
┌─────────────────────────────────────────────────────────────────┐
│  🔍 链上持仓监控                                                 │
│                                                                  │
│  ┌──── 链状态 ─────────────────────────────────────────┐         │
│  │  ⛓ ETH   最新区块: 21,234,567   延迟: 2s ago        │         │
│  │  ⛓ BSC   最新区块: 38,901,234   延迟: 3s ago        │         │
│  └──────────────────────────────────────────────────────┘        │
│                                                                  │
│  ┌──── 添加监控任务 ────────────────────────────────────┐         │
│  │ 代币名称: [____________]  网络: [ETH ▼]              │         │
│  │ 合约地址: [0x_______________________________]        │         │
│  │ 关注地址: [0x_______________________________]        │         │
│  │          [+ 添加更多地址]                            │         │
│  │ 告警阈值: 代币数量 [__________] 或                   │         │
│  │           USD 价值 [50] K  (默认 50K)               │         │
│  │          [✅ 保存任务]                               │         │
│  └──────────────────────────────────────────────────────┘        │
│                                                                  │
│  ┌──── 监控任务列表 ────────────────────────────────────┐         │
│  │ 代币   网络  合约             地址数  阈值   状态  操作 │        │
│  │ STO    BSC   0xabc...         3       50K USD  ✅  🗑   │        │
│  │ USDT   ETH   0xdai...         1       100K USD ✅  🗑   │        │
│  └──────────────────────────────────────────────────────┘        │
└─────────────────────────────────────────────────────────────────┘
```

---

## 三、数据库设计

### 3.1 `onchain_watch` — 监控任务表

```sql
CREATE TABLE IF NOT EXISTS onchain_watch (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_name     VARCHAR(50)  NOT NULL COMMENT '代币名称，如 STO',
    contract_addr  VARCHAR(42)  NOT NULL COMMENT '合约地址 0x...',
    network        VARCHAR(10)  NOT NULL COMMENT 'ETH | BSC',
    token_decimals TINYINT      NOT NULL DEFAULT 18 COMMENT '代币精度，首次查链获取',
    -- 阈值配置（二选一）
    threshold_mode VARCHAR(10)  NOT NULL DEFAULT 'USD' COMMENT 'TOKEN | USD',
    threshold_amount DECIMAL(30,4) COMMENT '代币数量阈值（TOKEN 模式）',
    threshold_usd  DECIMAL(20,2) NOT NULL DEFAULT 50000 COMMENT 'USD 阈值（USD 模式，默认 50000）',
    -- 关注地址（JSON 数组，最多 20 个）
    watched_addrs  JSON         NOT NULL COMMENT '["0xABC","0xDEF"]',
    -- 状态
    is_active      TINYINT(1)   NOT NULL DEFAULT 1,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_network (network),
    INDEX idx_active  (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.2 `onchain_holder_snapshot` — 余额快照表

```sql
CREATE TABLE IF NOT EXISTS onchain_holder_snapshot (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    watch_id       BIGINT       NOT NULL COMMENT 'FK -> onchain_watch.id',
    wallet_addr    VARCHAR(42)  NOT NULL COMMENT '钱包地址',
    balance_raw    DECIMAL(40,0) NOT NULL COMMENT '原始余额（不除以精度）',
    balance_token  DECIMAL(30,6) NOT NULL COMMENT '余额（除以精度后）',
    price_usd      DECIMAL(20,8) COMMENT '快照时代币 USD 价格',
    value_usd      DECIMAL(20,2) COMMENT '折算 USD 市值',
    block_number   BIGINT       NOT NULL COMMENT '取余额时的区块高度',
    snapped_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_watch_wallet (watch_id, wallet_addr),
    INDEX idx_snapped_at   (snapped_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

---

## 四、后端实现

### 4.1 RPC 调用方式

使用各链的**公开 JSON-RPC**，无需 API Key（限速较低，对监控场景足够）：

| 链   | RPC Endpoint（可配置）                              |
|------|-----------------------------------------------------|
| ETH  | `https://eth.llamarpc.com`                          |
| BSC  | `https://bsc-dataseed.binance.org/`                 |

**获取最新区块高度**
```json
POST /
{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}
→ {"result":"0x144a5f7"} // hex → Long
```

**查询 ERC20 balanceOf(address)**
```json
POST /
{
  "jsonrpc":"2.0","method":"eth_call",
  "params":[{
    "to":"0x{contractAddr}",
    "data":"0x70a08231000000000000000000000000{walletAddr_no0x_padded32}"
  },"latest"],
  "id":1
}
→ {"result":"0x000...0064"} // hex → BigInteger
```

**查询 ERC20 decimals()**
```json
"data":"0x313ce567"  // decimals() function selector
```

**价格来源**：Binance 现货 `GET https://api.binance.com/api/v3/ticker/price?symbol={TOKEN}USDT`

### 4.2 类结构

```
model/
  OnchainWatch.java              // @Entity, watched_addrs 用 @Convert(JSON)
  OnchainHolderSnapshot.java     // @Entity

repository/
  OnchainWatchRepository.java    // findAllByIsActiveTrue()
  OnchainHolderSnapshotRepository.java
    // findTopByWatchIdAndWalletAddrOrderBySnappedAtDesc()

service/
  OnchainRpcClient.java          // WebClient, eth_blockNumber / eth_call
  OnchainHolderService.java      // 业务逻辑：余额对比、飞书告警

job/
  OnchainHolderJob.java          // @Scheduled fixedDelay=60s

controller/
  OnchainHolderController.java   // REST CRUD + /api/onchain-holder/status
```

### 4.3 Job 执行流程

```
OnchainHolderJob.checkAll() (每 60s)
├── 1. 读取所有 is_active=1 的监控任务
├── 2. 对每个任务:
│   ├── a. 获取当前 Binance 价格（USD）
│   ├── b. 查询最新区块号
│   └── c. 对每个 watched_addrs:
│       ├── i.  eth_call balanceOf → balance_token
│       ├── ii. 查库获取上次快照余额
│       ├── iii. 计算差值 delta_token / delta_usd
│       ├── iv. 若 |delta_usd| >= threshold_usd → 飞书告警
│       └── v.  保存新快照
└── 3. 睡眠至下次调度
```

### 4.4 飞书告警格式

```
🚨 链上持仓变动告警

代币: STO (BSC)
钱包: 0xABC...DEF
变化: +12,345.67 STO（≈ $61,728 USD）↑
当前余额: 234,567.89 STO（≈ $1,172,839 USD）
当前价格: $5.00 USD
时间: 2026-04-06 14:30:00
合约: 0x{contractAddr}
```

---

## 五、API 设计

| 方法   | 路径                           | 说明                          |
|--------|-------------------------------|-------------------------------|
| GET    | `/onchain-holder`             | 页面（Thymeleaf）              |
| GET    | `/api/onchain-holder/list`    | 返回所有监控任务                |
| POST   | `/api/onchain-holder/add`     | 新增监控任务                    |
| DELETE | `/api/onchain-holder/{id}`    | 删除监控任务                    |
| PATCH  | `/api/onchain-holder/{id}/toggle` | 启用/禁用                  |
| GET    | `/api/onchain-holder/status`  | 返回 ETH/BSC 最新区块高度       |

### POST `/api/onchain-holder/add` 请求体

```json
{
  "tokenName": "STO",
  "contractAddr": "0xABC...",
  "network": "BSC",
  "thresholdMode": "USD",
  "thresholdUsd": 50000,
  "watchedAddrs": ["0xAAA...", "0xBBB..."]
}
```

---

## 六、配置（application.yml）

```yaml
onchain:
  eth-rpc: https://eth.llamarpc.com
  bsc-rpc: https://bsc-dataseed.binance.org/
```

---

## 七、边界情况处理

| 情况                         | 处理方式                                              |
|------------------------------|-------------------------------------------------------|
| RPC 超时/失败                 | 跳过本轮，不更新快照，log.warn                         |
| Binance 无该代币价格           | threshold_mode 自动降级为 TOKEN 模式比对               |
| 合约 decimals 查询失败         | 默认 18                                               |
| 首次添加任务（无快照）           | 立即拍一次快照作为基准，不告警                          |
| 同一钱包 60s 内多次告警         | 记录上次告警时间，同方向 5 分钟内不重复告警              |

---

## 八、待办清单

- [ ] DDL：`db/schema.sql` 追加两张表
- [ ] `model/` 两个实体
- [ ] `repository/` 两个 Repository
- [ ] `service/OnchainRpcClient.java`
- [ ] `service/OnchainHolderService.java`
- [ ] `job/OnchainHolderJob.java`
- [ ] `controller/OnchainHolderController.java`
- [ ] `templates/onchain-holder.html`
- [ ] `application.yml` 增加 `onchain` 配置块
- [ ] `layout.html` 已添加导航链接 ✅
