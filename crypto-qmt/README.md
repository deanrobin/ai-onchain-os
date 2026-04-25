# crypto-qmt

> BTC 15m K 线 + 技术指标 (MA20 / MA120 / MACD / RSI21) 看板,币安黑金风格。
> 脱胎自 [ai-onchain-os](../ai-onchain-os) 的 BTC 策略模块,独立成项目以便后续扩展为 QMT 量化看板。

## 技术栈

| 组件 | 版本 |
|------|------|
| JDK | 17 |
| Spring Boot | 3.4.3 |
| MySQL | 8.0 |
| Thymeleaf + Bootstrap 5 (CDN) | 自带 / 5.3.3 |
| Log4j2 (替代 logback) | 自带 |

## 数据库

宿主沿用 ai-onchain-os 那台 MySQL,但使用**独立 schema** `btc_kline_os`,避免与原项目冲突。

```bash
mysql -h43.134.118.142 -P33066 -uarb -p < db/schema.sql
```

## 构建与启动

```bash
# 1. 构建
./build.sh
# → target/crypto-qmt-0.1.0-SNAPSHOT.jar

# 2. 首次启动:启用历史 K 线导入(只跑一次)
BTC_KLINE_LOADER_ENABLED=true \
BTC_KLINE_LOADER_START=2024-01-01T00:00:00 \
./start.sh

# 3. 日常启动:关闭 loader,纯读 DB 渲染
./start.sh
```

打开 http://localhost:9900/btc-strategy 即看到币安黑金风格的看板。

## 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `DB_HOST` | `43.134.118.142` | MySQL 主机 |
| `DB_PORT` | `33066` | MySQL 端口 |
| `DB_USER` | `arb` | 用户名 |
| `DB_PASS` | `arb123456` | 密码(生产请改) |
| `BTC_KLINE_LOADER_ENABLED` | `false` | 是否启动时拉取历史 K 线 |
| `BTC_KLINE_LOADER_START` | `2024-01-01T00:00:00` | 拉取起始(上海时区) |
| `BTC_KLINE_LOADER_END` | `2026-04-25T00:00:00` | 拉取结束(上海时区) |
| `BTC_KLINE_LOADER_FORCE` | `false` | 忽略 DB 已有数据,从 start 重拉 |
| `BTC_KLINE_LOADER_RATE_MS` | `300` | Binance 每页间隔(ms) |

## 目录结构

```
crypto-qmt/
├── pom.xml
├── build.sh / start.sh
├── db/schema.sql                     # btc_kline_15m DDL
└── src/main/
    ├── java/com/deanrobin/cryptoqmt/
    │   ├── CryptoQmtApplication.java
    │   ├── config/BtcKlineLoaderConfig.java
    │   ├── controller/BtcStrategyController.java
    │   ├── job/BtcKlineLoaderJob.java   # 启动时从 Binance 拉历史
    │   ├── model/BtcKline15m.java
    │   └── repository/BtcKline15mRepository.java
    └── resources/
        ├── application.yml
        ├── log4j2.xml
        ├── static/css/binance-design.css
        └── templates/{layout.html, btc-strategy.html}
```

## 编码规范

- `BigDecimal` 构造一律用 `String` / `BigDecimal.valueOf(double)`,**禁止** `new BigDecimal(double)`
- 密钥用 `${ENV_VAR}` 占位符,**禁止**硬编码
- `ddl-auto=validate`,DDL 改动追加到 `db/schema.sql` 末尾(每批用 `YYYYMMDD-NNN` 分隔符)
