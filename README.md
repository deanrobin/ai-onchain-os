# ai-onchain-os

> AI 驱动的链上聪明钱追踪与自动跟单系统

## 功能

- 🔍 **聪明钱发现** — 自动识别高胜率链上钱包（胜率 ≥55%，PnL ≥$5k）
- 📡 **实时信号监控** — 检测聪明钱新交易，解析买入/卖出方向
- ⚡ **自动跟单执行** — 通过 OKX DEX Aggregator 按比例跟单
- 🛡️ **风险控制** — 代币安全检测 + 单笔/总仓位上限

## 支持链

Ethereum · BNB Chain · Base · Solana

## 架构

```
smart-money/ (Python) → signal-monitor/ (Java) → trade-executor/ (Java)
     聪明钱发现               实时信号检测              跟单执行
```

详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## 模块

| 模块 | 技术 | 说明 |
|------|------|------|
| `smart-money` | Python 3 | 聪明钱评分与发现，每 6h 运行 |
| `signal-monitor` | Java 17 + Spring Boot | 实时监控钱包交易，产出信号 |
| `trade-executor` | Java 17 + Spring Boot | 信号过滤 + DEX 执行 |
| `common` | Java 17 | 共享模型（Chain、TradeSignal 等） |

## 快速开始

```bash
# Python 模块
cd smart-money
cp .env.example .env   # 填入 OKX API Key 等
pip install -r requirements.txt
python3 main.py

# Java 模块
mvn clean package -DskipTests
```

## 依赖 API

- OKX OnChainOS: https://web3.okx.com/zh-hans/onchainos/dev-docs
- GoPlus Security: https://docs.gopluslabs.io
