package com.deanrobin.aios.dashboard.service;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * ETH / BSC JSON-RPC 客户端。
 *
 * <p>使用公开 RPC 节点，无需 API Key：
 * <ul>
 *   <li>ETH: https://eth.llamarpc.com</li>
 *   <li>BSC: https://bsc-dataseed.binance.org/</li>
 * </ul>
 *
 * <p>支持：
 * <ul>
 *   <li>{@link #getBlockNumber(String)} — eth_blockNumber</li>
 *   <li>{@link #getErc20Balance(String, String, String)} — balanceOf(address)</li>
 *   <li>{@link #getErc20Decimals(String, String)} — decimals()</li>
 * </ul>
 */
@Log4j2
@Service
public class OnchainRpcClient {

    @Value("${onchain.eth-rpc:https://eth.llamarpc.com}")
    private String ethRpc;

    @Value("${onchain.bsc-rpc:https://bsc-dataseed.binance.org/}")
    private String bscRpc;

    private final WebClient.Builder webClientBuilder;

    public OnchainRpcClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    // ── 获取最新区块号 ──────────────────────────────────────────────

    public Long getBlockNumber(String network) {
        try {
            Map<?, ?> resp = rpcCall(rpcUrl(network), "eth_blockNumber", List.of());
            if (resp == null) return null;
            String hex = (String) resp.get("result");
            return hex == null ? null : Long.parseLong(hex.substring(2), 16);
        } catch (Exception e) {
            log.warn("⚠️ getBlockNumber {} 失败: {}", network, e.getMessage());
            return null;
        }
    }

    // ── 查询 ERC20 余额（原始整数，未除精度）────────────────────────

    public BigDecimal getErc20BalanceRaw(String network, String contractAddr, String walletAddr) {
        try {
            // balanceOf(address) selector = 0x70a08231
            // 参数：32 字节补零的地址
            String paddedAddr = padAddress(walletAddr);
            String data = "0x70a08231" + paddedAddr;
            String callResult = ethCall(network, contractAddr, data);
            if (callResult == null || callResult.equals("0x")) return BigDecimal.ZERO;
            return new BigDecimal(new BigInteger(callResult.substring(2), 16));
        } catch (Exception e) {
            log.warn("⚠️ getErc20BalanceRaw {}/{}/{} 失败: {}", network, contractAddr, walletAddr, e.getMessage());
            return null;
        }
    }

    // ── 查询 ERC20 精度 ──────────────────────────────────────────────

    public Integer getErc20Decimals(String network, String contractAddr) {
        try {
            // decimals() selector = 0x313ce567
            String callResult = ethCall(network, contractAddr, "0x313ce567");
            if (callResult == null || callResult.equals("0x")) return 18;
            return new BigInteger(callResult.substring(2), 16).intValue();
        } catch (Exception e) {
            log.warn("⚠️ getErc20Decimals {}/{} 失败，默认 18: {}", network, contractAddr, e.getMessage());
            return 18;
        }
    }

    // ── 内部工具方法 ─────────────────────────────────────────────────

    private String ethCall(String network, String toAddr, String data) {
        Map<String, String> callParams = Map.of("to", toAddr, "data", data);
        Map<?, ?> resp = rpcCall(rpcUrl(network), "eth_call", List.of(callParams, "latest"));
        if (resp == null) return null;
        return (String) resp.get("result");
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> rpcCall(String url, String method, Object params) {
        Map<String, Object> body = Map.of(
            "jsonrpc", "2.0",
            "method",  method,
            "params",  params,
            "id",      1
        );
        try {
            return webClientBuilder.baseUrl(url).build()
                .post()
                .uri("/")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("⚠️ RPC 调用失败 {}/{}: {}", url, method, e.getMessage());
            return null;
        }
    }

    private String rpcUrl(String network) {
        return "BSC".equalsIgnoreCase(network) ? bscRpc : ethRpc;
    }

    /**
     * 将 0x 前缀地址（40 字符）扩展为 64 字符的 ABI 编码参数（左补零）。
     */
    private static String padAddress(String addr) {
        String clean = addr.startsWith("0x") ? addr.substring(2) : addr;
        return "0".repeat(64 - clean.length()) + clean.toLowerCase();
    }
}
