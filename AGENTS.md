# AGENTS.md — ai-onchain-os 项目规范

> 给所有接手这个项目的 AI Agent 看的。每条规则背后都有一次真实的踩坑。
> 遇到新问题修复后，在对应章节追加一行规则。

---

## 项目简介

**ai-onchain-os** — 链上聪明钱追踪 + 新币监控系统

- **核心功能**：监控聪明钱钱包买入信号（BSC/SOL）、pump.fun 新币、four.meme 新币
- **技术栈**：Spring Boot 3.4.3 / JDK 17 / MySQL 8 / Thymeleaf / Bootstrap 5
- **运行端口**：9900
- **数据库**：`oc_os`，连接信息通过环境变量 `DB_HOST` / `DB_PORT` 注入，见 `application.yml`

---

## 模块结构

```
ai-onchain-os/
├── dashboard/          ← Spring Boot 主模块（唯一模块）
│   ├── job/            ← 定时任务
│   │   ├── SignalFetchJob.java      每10s抓聪明钱信号
│   │   ├── PumpSurvivorJob.java     新币8阶段存活检查
│   │   ├── WalletAnalyzeJob.java    钱包评分更新
│   │   └── PriceFetchJob.java       价格更新
│   ├── service/        ← 业务服务
│   │   ├── OkxApiClient.java        OKX API 调用（唯一出口）
│   │   ├── PumpPortalClient.java    pump.fun WSS 客户端
│   │   ├── FourMemeClient.java      four.meme WSS 客户端
│   │   └── SmartMoneyService.java   信号处理核心
│   ├── model/          ← JPA 实体
│   ├── repository/     ← Spring Data JPA
│   ├── controller/     ← REST API + Thymeleaf 页面
│   └── util/           ← CryptoUtil 等工具类
├── db/schema.sql       ← 数据库 DDL
└── 架构.md             ← 系统架构文档
```

---

## 硬性规则（禁止违反）

### 安全
- ❌ 禁止 hardcode 任何密钥、Token、DB 密码到 Java 代码或 yml
- ❌ 禁止 git commit 包含 `OKX_API_KEY`、`OKX_API_SECRET`、`OKX_PASSPHRASE` 明文
- ✅ 所有密钥通过环境变量注入，参考 `application.yml` 中的 `${OKX_API_KEY}` 写法

### 链配置
- ✅ 当前只支持 **BSC（chainIndex=56）** 和 **SOL（chainIndex=501）**
- ❌ 不要添加 ETH(1)、Base(8453) 或其他链的支持，除非明确要求
- ❌ 不要在代码里 hardcode chain 列表，从 `application.yml` `smart-money.jobs.signal-fetch.chains` 读取

### 数据库
- ✅ DB 名固定为 `oc_os`，不要改
- ✅ `spring.jpa.hibernate.ddl-auto=validate`，禁止改为 `update` 或 `create`
- ✅ DDL 改动必须同步写入 `db/schema.sql`
- ❌ 不要用 `ALTER TABLE ... IF NOT EXISTS`，MySQL 8 不支持，先 `SELECT COUNT(*)` 判断

---

## 日志规范

- **框架**：Log4j2（已排除 Spring Boot 默认 logback）
- **路径**：绝对路径 `/root/aios-dashboard/logs/info.log`（全量），`error.log`（仅ERROR）
- **charset**：所有 appender 必须 `charset="UTF-8"`，否则中文乱码
- **风格**：emoji + 中英混合，简洁打重点
  ```java
  log.info("📡 chain={} 获取={} 保存={}", chainIndex, fetched, saved);
  log.warn("⚠️ OKX 429 退避 {}ms token={}", backoff, addr.substring(0,10));
  log.error("❌ DB 连接失败", e);
  ```
- **级别**：可预期/降级错误用 `log.warn`，严重/不可恢复用 `log.error`
- ❌ 不要在循环里打 INFO 级别的新币接收日志（会淹没日志）

---

## 数据库并发规范（血泪教训）

### 大事务禁令
- ❌ 禁止在 `@Scheduled` 方法上加 `@Transactional`
- ✅ 每次 `repo.save()` 是独立小事务，这是正确的

### 乐观锁
- ✅ `PumpToken` 和 `FourMemeToken` 已加 `@jakarta.persistence.Version`
- ✅ 处理 token 循环时必须捕获 `OptimisticLockingFailureException` 并跳过

### 批量 UPDATE
- ✅ 批量 UPDATE 必须加 `LIMIT 500`，防止一次锁几万行
- ✅ 需要更新大量数据时用循环 `while (repo.batchUpdate() > 0)` 分批处理

---

## API 调用规范

### OKX API
- ✅ 所有 OKX 调用必须通过 `OkxApiClient`，禁止在其他地方直接调
- ✅ Web 请求（Controller）只能读 DB，禁止触发 OKX API 调用
- ✅ 429 错误必须退避重试，当前退避时间从 1600ms 开始递增

### WebSocket 客户端
- ✅ `PumpPortalClient`（SOL）和 `FourMemeClient`（BSC）断线后 30s 自动重连
- ❌ 新币接收日志不要打印，只打印异常和连接状态变化

---

## PumpSurvivorJob 规范

八阶段检查时间线：`10m → 20m → 30m → 45m → 1h → 4h → 12h → 24h`

- ✅ 每个阶段 `findDueForXxx` 查询必须加 `LIMIT 20`，不允许无限拉取
- ✅ 积压清理（skipStale）每次最多 500 行，需要循环调用
- ✅ 超过 2h 未到达 1h 阶段的 token 直接批量跳过
- ✅ 只有 24h 阶段才删除不达标 token（市值 < $10K）
- ✅ 只有 24h 阶段才写 `pump_market_cap_snapshot`
- ❌ 每个 token 之间必须 sleep 5000ms，防止 OKX 429

---

## 前端规范

- ✅ Thymeleaf 事件属性用 `data-*` + JS `this.dataset.*`
- ❌ 禁止用 `th:onclick="someMethod('${variable}')"` 传 Java 变量（XSS + 编译报错）
- ✅ 所有时间显示为 Asia/Shanghai (UTC+8)
- ✅ Dashboard 轻色系：白色卡片、hover = 浅蓝色，不用暗色

---

## 构建与部署

```bash
# 编译
cd /root/clawd/github/ai-onchain-os
mvn -pl dashboard -am clean package -DskipTests

# 部署（唯一正确方式）
cp dashboard/target/dashboard-0.1.0-SNAPSHOT.jar /root/aios-dashboard/
sh /root/aios-dashboard/start.sh

# 查看日志
tail -f /root/aios-dashboard/logs/info.log
tail -f /root/aios-dashboard/logs/error.log
```

- ❌ 不要用 `java -jar` 直接启动，要用 `start.sh`（里面有 KEY_DECRYPT_PASS 等环境变量）
- ✅ 启动成功标志：`🚀 aios-dashboard v0.1.0 started successfully in X.XXs | port=9900`

---

## 信号过滤规则

当前双层过滤：

1. **Job 层**（SignalFetchJob）：`min-amount-usd=5000`，`min-market-cap-usd=10000`
2. **DB 查询层**：`WHERE (market_cap_usd IS NULL OR market_cap_usd=0 OR (market_cap_usd>=10000 AND amount_usd/market_cap_usd<=0.5))`

- ✅ 市值 < $10K 的信号跳过
- ✅ buy/mcap > 50% 的信号跳过（异常砸盘信号）

---

## 提交规范

每次 `git commit` 后，检查本次改动是否影响以下文档：

| 改了什么 | 需要检查的文档 |
|---------|-------------|
| 新增/删除功能 | `README.md`、`架构.md` |
| 新增/修改规则/约束 | `AGENTS.md`、`CLAUDE.md` |
| 改了 DB 表结构 | `db/schema.sql`、`CLAUDE.md` 的表速查 |
| 改了部署方式 | `CLAUDE.md` 的部署步骤 |

**如有影响，在同一次或紧接着的 commit 里更新文档，不要让文档和代码脱节。**

---

## 踩坑记录（持续更新）

| 时间 | 问题 | 原因 | 修复 |
|------|------|------|------|
| 2026-03 | SignalFetchJob 停跑 | Spring 默认1个调度线程被 PumpSurvivorJob 占满 | `pool.size=4` |
| 2026-03 | DB 锁超时崩溃 | `run()` 上有 `@Transactional`，持有锁几十分钟 | 去掉大事务 |
| 2026-03 | skipStale 锁全表 | 一次 UPDATE 几万行 | 加 `LIMIT 500` 循环 |
| 2026-03 | 前端 `th:onclick` 报错 | Thymeleaf 变量嵌入字符串不安全 | 改用 `data-*` 属性 |
| 2026-03 | 中文日志乱码 | log4j2 appender 没加 charset | `charset="UTF-8"` |
| 2026-03 | mask 显示不对 | trim 没处理末尾换行符 | 先 trim 再取 last4 |
