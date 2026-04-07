package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.model.OnchainHolderSnapshot;
import com.deanrobin.aios.dashboard.model.OnchainWatch;
import com.deanrobin.aios.dashboard.repository.OnchainHolderSnapshotRepository;
import com.deanrobin.aios.dashboard.repository.OnchainWatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 链上持仓业务逻辑：
 * <ul>
 *   <li>轮询每个监控任务的余额</li>
 *   <li>与上次快照比对，超阈值发飞书告警</li>
 *   <li>同方向告警 5 分钟内不重复</li>
 * </ul>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class OnchainHolderService {

    @Value("${perp.alert-url}")
    private String feishuWebhook;

    private final OnchainWatchRepository         watchRepo;
    private final OnchainHolderSnapshotRepository snapshotRepo;
    private final OnchainRpcClient               rpcClient;
    private final WebClient.Builder              webClientBuilder;

    // key = watchId:walletAddr:direction(UP/DOWN), value = 上次告警时间
    private final ConcurrentHashMap<String, LocalDateTime> lastAlertTime = new ConcurrentHashMap<>();

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ── 主入口（由 Job 调用）──────────────────────────────────────────

    public void checkAll() {
        List<OnchainWatch> watches = watchRepo.findAllByIsActiveTrue();
        if (watches.isEmpty()) return;

        for (OnchainWatch watch : watches) {
            try {
                checkWatch(watch);
            } catch (Exception e) {
                log.warn("⚠️ 监控任务 {} ({}) 处理异常: {}", watch.getId(), watch.getTokenName(), e.getMessage());
            }
        }
    }

    // ── 单任务处理 ────────────────────────────────────────────────────

    private void checkWatch(OnchainWatch watch) {
        // 获取代币价格（Binance 现货）
        BigDecimal tokenPrice = fetchBinancePrice(watch.getTokenName());

        // 获取当前区块号
        Long blockNumber = rpcClient.getBlockNumber(watch.getNetwork());
        if (blockNumber == null) {
            log.warn("⚠️ 无法获取 {} 区块高度，跳过任务 {}", watch.getNetwork(), watch.getId());
            return;
        }

        for (String walletAddr : watch.getWatchedAddrs()) {
            try {
                processWallet(watch, walletAddr, blockNumber, tokenPrice);
            } catch (Exception e) {
                log.warn("⚠️ 处理钱包 {} 失败: {}", walletAddr, e.getMessage());
            }
        }
    }

    private void processWallet(OnchainWatch watch, String walletAddr,
                                long blockNumber, BigDecimal tokenPrice) {
        // 1. 获取当前链上余额
        BigDecimal balanceRaw = rpcClient.getErc20BalanceRaw(
                watch.getNetwork(), watch.getContractAddr(), walletAddr);
        if (balanceRaw == null) return;

        int decimals = watch.getTokenDecimals();
        BigDecimal divisor = BigDecimal.TEN.pow(decimals);
        BigDecimal balanceToken = balanceRaw.divide(divisor, 6, RoundingMode.DOWN);

        BigDecimal valueUsd = null;
        if (tokenPrice != null) {
            valueUsd = balanceToken.multiply(tokenPrice).setScale(2, RoundingMode.HALF_UP);
        }

        // 2. 查上次快照
        Optional<OnchainHolderSnapshot> prevOpt =
                snapshotRepo.findTopByWatchIdAndWalletAddrOrderBySnappedAtDesc(watch.getId(), walletAddr);

        if (prevOpt.isEmpty()) {
            // 首次快照，仅记录，不告警
            saveSnapshot(watch, walletAddr, balanceRaw, balanceToken, tokenPrice, valueUsd, blockNumber);
            log.info("📸 链上持仓首次快照 {}/{} balance={} {}", watch.getTokenName(), walletAddr, balanceToken, watch.getNetwork());
            return;
        }

        OnchainHolderSnapshot prev = prevOpt.get();
        BigDecimal deltaToken = balanceToken.subtract(prev.getBalanceToken());

        // 3. 判断是否超过阈值
        boolean triggered = false;
        if ("TOKEN".equals(watch.getThresholdMode()) && watch.getThresholdAmount() != null) {
            triggered = deltaToken.abs().compareTo(watch.getThresholdAmount()) >= 0;
        } else {
            // USD 模式
            if (tokenPrice != null && tokenPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal deltaUsd = deltaToken.abs().multiply(tokenPrice).setScale(2, RoundingMode.HALF_UP);
                triggered = deltaUsd.compareTo(watch.getThresholdUsd()) >= 0;
            }
        }

        if (triggered) {
            String direction = deltaToken.compareTo(BigDecimal.ZERO) >= 0 ? "UP" : "DOWN";
            String throttleKey = watch.getId() + ":" + walletAddr + ":" + direction;
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime lastAlert = lastAlertTime.get(throttleKey);

            if (lastAlert == null || Duration.between(lastAlert, now).toMinutes() >= 5) {
                sendFeishuAlert(watch, walletAddr, deltaToken, balanceToken, tokenPrice, valueUsd);
                lastAlertTime.put(throttleKey, now);
            }
        }

        // 4. 保存新快照
        saveSnapshot(watch, walletAddr, balanceRaw, balanceToken, tokenPrice, valueUsd, blockNumber);
    }

    private void saveSnapshot(OnchainWatch watch, String walletAddr,
                               BigDecimal balanceRaw, BigDecimal balanceToken,
                               BigDecimal priceUsd, BigDecimal valueUsd, long blockNumber) {
        OnchainHolderSnapshot snap = new OnchainHolderSnapshot();
        snap.setWatchId(watch.getId());
        snap.setWalletAddr(walletAddr.toLowerCase());
        snap.setBalanceRaw(balanceRaw);
        snap.setBalanceToken(balanceToken);
        snap.setPriceUsd(priceUsd);
        snap.setValueUsd(valueUsd);
        snap.setBlockNumber(blockNumber);
        snap.setSnappedAt(LocalDateTime.now());
        snapshotRepo.save(snap);
    }

    // ── 飞书告警 ─────────────────────────────────────────────────────

    private void sendFeishuAlert(OnchainWatch watch, String walletAddr,
                                  BigDecimal deltaToken, BigDecimal balanceToken,
                                  BigDecimal tokenPrice, BigDecimal valueUsd) {
        String direction = deltaToken.compareTo(BigDecimal.ZERO) >= 0 ? "↑ 增持" : "↓ 减仓";
        String deltaStr  = (deltaToken.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") +
                           fmt(deltaToken.abs()) + " " + watch.getTokenName();
        String deltaUsdStr = tokenPrice != null
                ? "（≈ $" + fmt(deltaToken.abs().multiply(tokenPrice)) + " USD）"
                : "";
        String balanceStr = fmt(balanceToken) + " " + watch.getTokenName();
        String valueUsdStr = valueUsd != null ? "（≈ $" + fmt(valueUsd) + " USD）" : "";
        String priceStr   = tokenPrice != null ? "$" + tokenPrice.toPlainString() + " USD" : "未知";
        String shortAddr  = shortAddr(walletAddr);

        String text = "🚨 链上持仓变动告警\n\n" +
                "代币: " + watch.getTokenName() + " (" + watch.getNetwork() + ")\n" +
                "钱包: " + shortAddr + "\n" +
                "变化: " + deltaStr + deltaUsdStr + " " + direction + "\n" +
                "当前余额: " + balanceStr + valueUsdStr + "\n" +
                "当前价格: " + priceStr + "\n" +
                "时间: " + LocalDateTime.now().format(FMT) + "\n" +
                "合约: " + watch.getContractAddr();

        Map<String, Object> body = Map.of(
            "msg_type", "text",
            "content",  Map.of("text", text)
        );

        try {
            webClientBuilder.build()
                .post()
                .uri(feishuWebhook)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(5));
            log.info("📣 飞书告警已发送 {}/{}", watch.getTokenName(), shortAddr);
        } catch (Exception e) {
            log.warn("⚠️ 飞书告警发送失败: {}", e.getMessage());
        }
    }

    // ── Binance 价格 ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public BigDecimal fetchBinancePrice(String tokenName) {
        try {
            String symbol = tokenName.toUpperCase() + "USDT";
            Map<?, ?> resp = webClientBuilder.baseUrl("https://api.binance.com").build()
                .get()
                .uri("/api/v3/ticker/price?symbol=" + symbol)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(5));
            if (resp == null) return null;
            Object price = resp.get("price");
            return price == null ? null : new BigDecimal(String.valueOf(price));
        } catch (Exception e) {
            log.debug("Binance 价格查询失败 {}: {}", tokenName, e.getMessage());
            return null;
        }
    }

    // ── 工具方法 ──────────────────────────────────────────────────────

    /** 格式化数字，加千位分隔符 */
    private static String fmt(BigDecimal v) {
        if (v == null) return "0";
        return String.format("%,.2f", v);
    }

    /** 地址缩写：0xABCD...WXYZ */
    private static String shortAddr(String addr) {
        if (addr == null || addr.length() < 10) return addr;
        return addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4);
    }
}
