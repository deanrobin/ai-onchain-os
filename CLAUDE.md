# CLAUDE.md — ai-onchain-os 编码指南

> 给 Claude Code / Codex 等编程 Agent 的快速入门文档。
> 详细规范见 `AGENTS.md`，本文件是精简版入口，控制在可快速读完的范围内。

---

## 技术版本（固定，不要升级除非明确要求）

| 组件 | 版本 |
|------|------|
| **JDK** | 17 |
| **Spring Boot** | 3.4.3 |
| **MySQL** | 8.0 |
| **Maven** | 最新稳定版 |
| **Lombok** | Spring Boot 自带版本 |
| **Log4j2** | Spring Boot 自带版本（已排除 logback）|
| **Thymeleaf** | Spring Boot 自带版本 |
| **Bootstrap** | 5（CDN 引入）|

> ❌ 不要擅自升级 Spring Boot / JDK 版本，可能破坏现有 API 兼容性。

---

## 这是什么项目

链上聪明钱追踪系统。监控聪明钱钱包的买入信号，以及 pump.fun / four.meme 新币的存活情况。

**不是交易执行系统**，是信息展示 + 信号筛选系统。

---

## 启动前必读

```bash
# 唯一正确的构建命令
mvn -pl dashboard -am clean package -DskipTests

# 唯一正确的启动方式
sh /root/aios-dashboard/start.sh

# 成功标志
# 🚀 aios-dashboard v0.1.0 started successfully in X.XXs | port=9900
```

❌ 不要用 `java -jar` 直接跑，缺环境变量会启动失败。

---

## 核心约束（高优先级）

1. **不要动链配置** — 当前只有 BSC(56) + SOL(501)，不加其他链
2. **不要 hardcode 密钥** — 全部用 `${ENV_VAR}` 形式，看 `application.yml`
3. **不要改 ddl-auto** — 保持 `validate`，DDL 改动写 `db/schema.sql`
4. **不要在 Controller 里调 OKX API** — Web 请求只读 DB
5. **不要给 `@Scheduled` 方法加 `@Transactional`** — 会导致长事务锁死 DB

---

## 代码位置速查

| 要改什么 | 去哪里 |
|---------|--------|
| 信号抓取逻辑 | `job/SignalFetchJob.java` |
| 新币存活检查 | `job/PumpSurvivorJob.java` |
| OKX API 调用 | `service/OkxApiClient.java` |
| 聪明钱评分 | `service/WalletScorerService.java` |
| 页面模板 | `resources/templates/*.html` |
| 数据库 DDL | `db/schema.sql` |
| 调度/DB 配置 | `resources/application.yml` |
| 日志配置 | `resources/log4j2.xml` |

---

## 数据库表速查

| 表名 | 用途 |
|------|------|
| `smart_money_signal` | 聪明钱买入信号 |
| `smart_money_wallet` | 聪明钱钱包评分 |
| `pump_token` | pump.fun 新币（SOL）|
| `four_meme_token` | four.meme 新币（BSC）|
| `pump_market_cap_snapshot` | 新币24h市值快照 |
| `price_ticker` | 实时价格（BNB/SOL/ETH）|
| `my_address` | 我的钱包地址 |
| `binance_square_post` | 币安广场帖子原文（7 天保留）|
| `binance_square_token_stat` | 币安广场代币热度（7 天保留）|

---

## 日志风格

```java
// 正确示范
log.info("📡 chain={} 获取={} 保存={}", chain, fetched, saved);
log.warn("⚠️ OKX 429 退避 {}ms", backoff);
log.error("❌ DB 异常", e);

// 错误示范 — 不要在新币接收循环里打 INFO
log.info("收到新币: {}", token);  // ❌ 会刷爆日志
```

---

## 改完之后

```bash
# 1. 构建
mvn -pl dashboard -am clean package -DskipTests

# 2. 确认 BUILD_OK 再继续

# 3. 部署
cp dashboard/target/dashboard-0.1.0-SNAPSHOT.jar /root/aios-dashboard/
sh /root/aios-dashboard/start.sh

# 4. 验证
sleep 20 && grep "started successfully\|ERROR" /root/aios-dashboard/logs/info.log | tail -5

# 5. 提交
git add -A && git commit -m "feat/fix: 描述" && git push
```

---

## 详细规范

完整规则、踩坑记录、并发规范见 [`AGENTS.md`](./AGENTS.md)。
