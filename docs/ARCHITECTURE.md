# ai-onchain-os 架构设计

## 目标

1. **聪明钱发现**：自动识别链上高胜率、高盈利钱包
2. **实时监控**：检测聪明钱的新买入/卖出信号
3. **跟单执行**：通过 OKX DEX Aggregator 自动执行跟单

## 支持的链

| 链 | OKX chainIndex |
|----|---------------|
| Ethereum | 1 |
| BNB Chain | 56 |
| Base | 8453 |
| Solana | 501 |

## 系统架构

```
┌───────────────────────────────────────────────────────────┐
│  smart-money/ (Python)                                    │
│  每 6 小时运行一次                                         │
│                                                           │
│  候选地址来源                                              │
│  ├── OKX OnChainOS: token top buyers / large txns         │
│  └── 手动添加种子地址                                      │
│                                                           │
│  评分标准                                                  │
│  ├── 胜率 ≥ 55%                                           │
│  ├── 交易次数 ≥ 20                                        │
│  └── 累计盈利 ≥ $5,000                                    │
│                                                           │
│  输出 → DB: smart_money_wallet                            │
└────────────────────────┬──────────────────────────────────┘
                         │ 聪明钱地址列表
┌────────────────────────▼──────────────────────────────────┐
│  signal-monitor/ (Java Spring Boot)                       │
│                                                           │
│  每 30 秒轮询一次（或 WebSocket 实时推送）                 │
│  ├── 监控 smart_money_wallet 表中的地址                   │
│  ├── 调用 OKX API 获取最新交易                            │
│  ├── 解析 DEX Swap 交易 → 识别买入/卖出                   │
│  ├── 代币安全检测（GoPlus API）                           │
│  └── 写入 DB: trade_signal 表                            │
└────────────────────────┬──────────────────────────────────┘
                         │ TradeSignal
┌────────────────────────▼──────────────────────────────────┐
│  trade-executor/ (Java Spring Boot)                       │
│                                                           │
│  风控过滤                                                  │
│  ├── 信号延迟 ≤ 60s（太旧的跳过）                         │
│  ├── 代币黑名单 / 最低流动性                              │
│  ├── 持仓上限（同一代币不重复买入）                        │
│  └── 单笔最大金额限制                                      │
│                                                           │
│  执行                                                      │
│  ├── OKX DEX Aggregator: 获取最优报价                     │
│  ├── EVM 链：web3j 签名 + 广播交易                        │
│  └── Solana：调用 Python 脚本 or solana-py                │
│                                                           │
│  输出 → DB: copy_trade_record                             │
└───────────────────────────────────────────────────────────┘
```

## 数据库表设计（草稿）

| 表名 | 说明 |
|------|------|
| `smart_money_wallet` | 聪明钱地址列表，含评分、链、最后更新时间 |
| `trade_signal` | 信号记录，含来源地址、代币、方向、金额、链 |
| `copy_trade_record` | 跟单执行记录，含 txHash、实际金额、状态 |
| `token_blacklist` | 手动/自动加入的代币黑名单 |

## 仓位管理规则（待细化）

- **跟单金额** = 聪明钱原始金额 × 比例系数（默认 0.1，即 10%）
- **单笔上限** = 配置项 `MAX_SINGLE_TRADE_USD`（默认 $500）
- **单代币持仓上限** = 配置项 `MAX_TOKEN_POSITION_USD`（默认 $1,000）
- **总持仓上限** = 配置项 `MAX_TOTAL_POSITION_USD`（默认 $5,000）

## API 依赖

| 功能 | API |
|------|-----|
| 链上数据 / 交易历史 | OKX OnChainOS Open API |
| DEX 执行 | OKX DEX Aggregator API |
| 代币安全检测 | GoPlus Security API（免费） |
| EVM 交易签名 | web3j（Java） |
| Solana 交易签名 | solana-py（Python） |

## 开发计划

- [ ] Phase 1：搭建骨架（当前）
- [ ] Phase 2：实现聪明钱评分（Python scorer.py）
- [ ] Phase 3：实现 signal-monitor 轮询逻辑
- [ ] Phase 4：实现 trade-executor + 风控
- [ ] Phase 5：上线测试（小额真实资金）
