package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.vo.TradeResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 交易执行服务
 * <p>
 * 支持链：BSC (chainId=56)、Solana (chainId=501)
 * 私钥从环境变量读取：
 *   - WALLET_PRIVATE_KEY_EVM  : BSC  (hex，可选 0x 前缀；加密时格式 ENC:{base64iv}:{base64cipher})
 *   - WALLET_PRIVATE_KEY_SOL  : SOL  (Base58 64-byte keypair；加密同上)
 *   - WALLET_KEY_PASSWORD     : AES 解密密码（仅加密模式需要）
 * 滑点/gas：优先使用 OKX API 返回值，兜底 10%
 * 重试策略：
 *   - 获取 swap 数据：最多 3 次，间隔 1 s
 *   - 广播交易：最多 3 次，间隔 500 ms
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class TradeService {

    private static final String BSC_RPC       = "https://bsc-dataseed.binance.org/";
    private static final String SOL_RPC       = "https://api.mainnet-beta.solana.com";
    private static final String BSC_NATIVE    = "0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE";
    private static final String SOL_NATIVE    = "11111111111111111111111111111111";
    private static final long   BSC_CHAIN_ID  = 56L;
    private static final int    MAX_RETRIES   = 3;
    private static final long   SWAP_RETRY_MS = 1000L;
    private static final long   BCAST_RETRY_MS = 500L;
    private static final String DEFAULT_SLIPPAGE = "0.1";   // 10 %

    private final OkxApiClient okxApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─────────────────────────── PUBLIC API ───────────────────────────

    public TradeResult executeTrade(String chain, String tokenCA, String amount) {
        return executeTrade(chain, tokenCA, amount, null);
    }

    public TradeResult executeTrade(String chain, String tokenCA, String amount, String slippage) {
        // 滑点处理：传入值优先，兜底 DEFAULT_SLIPPAGE（10%）
        String resolvedSlippage = resolveSlippage(slippage);
        try {
            return switch (chain.toLowerCase()) {
                case "bsc", "56"  -> executeBscTrade(tokenCA, amount, resolvedSlippage);
                case "sol", "501" -> executeSolTrade(tokenCA, amount, resolvedSlippage);
                default -> TradeResult.error("不支持的链: " + chain + "（仅支持 bsc / sol）");
            };
        } catch (Exception e) {
            log.error("Trade failed chain={} tokenCA={} amount={} slippage={}", chain, tokenCA, amount, resolvedSlippage, e);
            return TradeResult.error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * 解析滑点值，返回 OKX API 格式（0~1 的小数字符串，如 "0.1" 表示 10%）。
     * 支持：null/空 → 默认10%；"10" → 解析为 0.10（百分比格式）；"0.1" → 直接使用。
     */
    private String resolveSlippage(String slippage) {
        if (slippage == null || slippage.isBlank()) return DEFAULT_SLIPPAGE;
        slippage = slippage.trim();
        try {
            BigDecimal val = new BigDecimal(slippage);
            // 若大于 1，视为百分比格式（如 "10" → 0.10）
            if (val.compareTo(BigDecimal.ONE) > 0) {
                val = val.divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
            }
            // 限制范围 0.001 ~ 0.5（0.1% ~ 50%）
            if (val.compareTo(new BigDecimal("0.001")) < 0) val = new BigDecimal("0.001");
            if (val.compareTo(new BigDecimal("0.5"))   > 0) val = new BigDecimal("0.5");
            return val.stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            log.warn("⚠️ 滑点参数解析失败 slippage={}，使用默认值 {}", slippage, DEFAULT_SLIPPAGE);
            return DEFAULT_SLIPPAGE;
        }
    }

    // ─────────────────────────── BSC ──────────────────────────────────

    private TradeResult executeBscTrade(String tokenCA, String amount, String slippage) throws Exception {
        String rawKey = loadEnvKey("WALLET_PRIVATE_KEY_EVM");
        Credentials credentials = Credentials.create(rawKey);
        String walletAddress = credentials.getAddress();
        log.info("BSC trade: wallet={} tokenCA={} amount={} BNB", walletAddress, tokenCA, amount);

        // amount → wei
        BigInteger amountWei = new BigDecimal(amount)
                .multiply(BigDecimal.TEN.pow(18))
                .toBigInteger();

        // ── 1. Get swap data from OKX DEX (retry 3×, 1 s) ──
        log.info("BSC slippage={} ({}%)", slippage, new BigDecimal(slippage).multiply(BigDecimal.valueOf(100)).toPlainString());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("chainId",          "56");
        params.put("fromTokenAddress", BSC_NATIVE);
        params.put("toTokenAddress",   tokenCA);
        params.put("amount",           amountWei.toString());
        params.put("slippage",         slippage);
        params.put("userWalletAddress", walletAddress);

        Map<?, ?> swapResp = callOkxSwapWithRetry(params);

        // ── 2. Parse tx fields ──
        Map<?, ?> txData   = extractTx(swapResp);
        String    data     = str(txData, "data", "0x");
        String    toAddr   = str(txData, "to",   "");
        BigInteger gasLimit = bigInt(txData, "gas",      BigInteger.valueOf(300_000));
        BigInteger gasPrice = bigInt(txData, "gasPrice", BigInteger.valueOf(5_000_000_000L));
        BigInteger value    = bigInt(txData, "value",    amountWei);

        // ── 3. Get nonce ──
        Web3j web3j = Web3j.build(new HttpService(BSC_RPC));
        EthGetTransactionCount txCount = web3j
                .ethGetTransactionCount(walletAddress, DefaultBlockParameterName.LATEST)
                .send();
        BigInteger nonce = txCount.getTransactionCount();
        log.info("BSC nonce={} gasPrice={} gasLimit={}", nonce, gasPrice, gasLimit);

        // ── 4. Sign ──
        RawTransaction rawTx = RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, toAddr, value, data);
        byte[] signed = TransactionEncoder.signMessage(rawTx, BSC_CHAIN_ID, credentials);
        String hexSigned = Numeric.toHexString(signed);

        // ── 5. Broadcast (retry 3×, 500 ms) ──
        return broadcastBsc(web3j, hexSigned);
    }

    private TradeResult broadcastBsc(Web3j web3j, String hexSigned) throws Exception {
        Exception lastErr = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                EthSendTransaction resp = web3j.ethSendRawTransaction(hexSigned).send();
                String hash = resp.getTransactionHash();
                if (hash != null && !hash.isEmpty()) {
                    log.info("BSC broadcast success txHash={}", hash);
                    return TradeResult.success(hash);
                }
                if (resp.getError() != null) {
                    lastErr = new RuntimeException("BSC RPC: " + resp.getError().getMessage());
                    log.warn("BSC broadcast attempt {} error: {}", i + 1, resp.getError().getMessage());
                }
            } catch (Exception e) {
                lastErr = e;
                log.warn("BSC broadcast attempt {} exception: {}", i + 1, e.getMessage());
            }
            if (i < MAX_RETRIES - 1) Thread.sleep(BCAST_RETRY_MS);
        }
        throw lastErr != null ? lastErr : new RuntimeException("BSC 广播失败（已重试 3 次）");
    }

    // ─────────────────────────── SOLANA ───────────────────────────────

    private TradeResult executeSolTrade(String tokenCA, String amount, String slippage) throws Exception {
        String rawKey = loadEnvKey("WALLET_PRIVATE_KEY_SOL");
        byte[] keypair = base58Decode(rawKey.trim());
        // Solana keypair: first 32 bytes = Ed25519 seed, last 32 = public key
        if (keypair.length < 32) {
            throw new IllegalArgumentException("SOL 私钥长度不足（需要 32 或 64 字节 base58）");
        }
        byte[] seed = Arrays.copyOf(keypair, 32);
        Ed25519PrivateKeyParameters privKey = new Ed25519PrivateKeyParameters(seed, 0);
        byte[] pubKeyBytes = privKey.generatePublicKey().getEncoded();
        String walletAddress = base58Encode(pubKeyBytes);
        log.info("SOL trade: wallet={} tokenCA={} amount={} SOL", walletAddress, tokenCA, amount);

        // amount → lamports
        BigInteger lamports = new BigDecimal(amount)
                .multiply(BigDecimal.valueOf(1_000_000_000L))
                .toBigInteger();

        // ── 1. Get swap data from OKX DEX (retry 3×, 1 s) ──
        log.info("SOL slippage={} ({}%)", slippage, new BigDecimal(slippage).multiply(BigDecimal.valueOf(100)).toPlainString());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("chainId",          "501");
        params.put("fromTokenAddress", SOL_NATIVE);
        params.put("toTokenAddress",   tokenCA);
        params.put("amount",           lamports.toString());
        params.put("slippage",         slippage);
        params.put("userWalletAddress", walletAddress);

        Map<?, ?> swapResp = callOkxSwapWithRetry(params);

        // ── 2. Get serialized transaction ──
        Map<?, ?> txData  = extractTx(swapResp);
        String    base64Tx = str(txData, "data", "");
        if (base64Tx.isEmpty()) {
            throw new RuntimeException("OKX 未返回 SOL 交易数据 (tx.data 为空)");
        }
        byte[] txBytes = Base64.getDecoder().decode(base64Tx);

        // ── 3. Sign ──
        byte[] signedTx = signSolanaTransaction(txBytes, privKey);
        String signedBase64 = Base64.getEncoder().encodeToString(signedTx);

        // ── 4. Broadcast (retry 3×, 500 ms) ──
        return broadcastSol(signedBase64);
    }

    /**
     * Solana legacy transaction signing.
     * Layout: [compact-u16 numSigs][sig slots: numSigs×64][message...]
     * For numSigs=1 and compact-u16 value 1 → byte[0]=0x01, signature at [1..64], message at [65..]
     */
    private byte[] signSolanaTransaction(byte[] txBytes, Ed25519PrivateKeyParameters privKey) {
        // Decode compact-u16 at position 0 to find number of signatures
        int numSigs = decodeCompactU16(txBytes, 0);
        int headerLen = compactU16Len(numSigs);       // bytes used to encode numSigs
        int sigSectionLen = headerLen + numSigs * 64; // total bytes before message
        if (txBytes.length <= sigSectionLen) {
            throw new IllegalArgumentException("Solana 交易长度不足，无法提取消息体");
        }
        byte[] message = Arrays.copyOfRange(txBytes, sigSectionLen, txBytes.length);

        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privKey);
        signer.update(message, 0, message.length);
        byte[] signature = signer.generateSignature();

        byte[] result = txBytes.clone();
        System.arraycopy(signature, 0, result, headerLen, 64); // fill first sig slot
        return result;
    }

    private TradeResult broadcastSol(String signedBase64) throws Exception {
        String reqBody = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "sendTransaction",
                "params", List.of(signedBase64, Map.of(
                        "encoding", "base64",
                        "preflightCommitment", "confirmed"
                ))
        ));
        Exception lastErr = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                String respStr = WebClient.create(SOL_RPC)
                        .post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(reqBody)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                @SuppressWarnings("unchecked")
                Map<String, Object> resp = objectMapper.readValue(respStr, Map.class);
                if (resp.containsKey("result") && resp.get("result") != null) {
                    String txHash = resp.get("result").toString();
                    log.info("SOL broadcast success txHash={}", txHash);
                    return TradeResult.success(txHash);
                }
                if (resp.containsKey("error")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> err = (Map<String, Object>) resp.get("error");
                    lastErr = new RuntimeException("Solana RPC: " + err.get("message"));
                    log.warn("SOL broadcast attempt {} error: {}", i + 1, err.get("message"));
                }
            } catch (Exception e) {
                lastErr = e;
                log.warn("SOL broadcast attempt {} exception: {}", i + 1, e.getMessage());
            }
            if (i < MAX_RETRIES - 1) Thread.sleep(BCAST_RETRY_MS);
        }
        throw lastErr != null ? lastErr : new RuntimeException("SOL 广播失败（已重试 3 次）");
    }

    // ─────────────────────────── OKX HELPER ───────────────────────────

    private Map<?, ?> callOkxSwapWithRetry(Map<String, String> params) throws Exception {
        Exception lastErr = null;
        Map<?, ?> swapResp = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                swapResp = okxApiClient.getWeb3("/api/v5/dex/aggregator/swap", params);
                String code = swapResp.get("code") != null ? swapResp.get("code").toString() : "-1";
                if ("0".equals(code)) {
                    log.info("OKX swap data fetched on attempt {}", i + 1);
                    return swapResp;
                }
                lastErr = new RuntimeException("OKX swap 失败 code=" + code + " msg=" + swapResp.get("msg"));
                log.warn("OKX swap attempt {} failed: {}", i + 1, swapResp.get("msg"));
            } catch (Exception e) {
                lastErr = e;
                log.warn("OKX swap attempt {} exception: {}", i + 1, e.getMessage());
            }
            if (i < MAX_RETRIES - 1) Thread.sleep(SWAP_RETRY_MS);
        }
        throw lastErr != null ? lastErr : new RuntimeException("OKX swap API 调用失败（已重试 3 次）");
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> extractTx(Map<?, ?> swapResp) {
        Object dataObj = swapResp.get("data");
        if (dataObj instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> map && map.containsKey("tx")) {
                return (Map<?, ?>) map.get("tx");
            }
        }
        throw new RuntimeException("OKX 响应中未找到 tx 字段: " + swapResp);
    }

    // ─────────────────────────── KEY LOADING ──────────────────────────

    /**
     * 从环境变量加载私钥，支持明文和 AES 加密格式。
     * 加密格式：ENC:{base64(16-byte IV)}:{base64(ciphertext)}
     * 解密密码来自环境变量 WALLET_KEY_PASSWORD。
     */
    private String loadEnvKey(String envName) {
        String val = System.getenv(envName);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("环境变量 " + envName + " 未设置");
        }
        val = val.trim();
        if (val.startsWith("ENC:")) {
            return decryptAes(val.substring(4));
        }
        return val;
    }

    private String decryptAes(String payload) {
        // payload = "{base64iv}:{base64cipher}"
        int colon = payload.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException("加密私钥格式错误（期望 ENC:{iv}:{cipher}）");
        try {
            byte[] iv     = Base64.getDecoder().decode(payload.substring(0, colon));
            byte[] cipher = Base64.getDecoder().decode(payload.substring(colon + 1));
            String password = System.getenv("WALLET_KEY_PASSWORD");
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("环境变量 WALLET_KEY_PASSWORD 未设置（解密私钥需要）");
            }
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(password.getBytes(StandardCharsets.UTF_8));
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
            return new String(c.doFinal(cipher), StandardCharsets.UTF_8).trim();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("AES 解密私钥失败: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────── UTILS ────────────────────────────────

    private static String str(Map<?, ?> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }

    private static BigInteger bigInt(Map<?, ?> m, String key, BigInteger def) {
        Object v = m.get(key);
        if (v == null) return def;
        try { return new BigInteger(v.toString()); } catch (Exception e) { return def; }
    }

    /** Decode compact-u16 integer (Solana wire format) at given offset. */
    private static int decodeCompactU16(byte[] buf, int offset) {
        int val  = buf[offset] & 0xFF;
        if ((val & 0x80) == 0) return val;
        val = (val & 0x7F) | ((buf[offset + 1] & 0x7F) << 7);
        if ((buf[offset + 1] & 0x80) == 0) return val;
        return val | ((buf[offset + 2] & 0xFF) << 14);
    }

    /** Bytes used to encode a compact-u16 value in Solana wire format. */
    private static int compactU16Len(int val) {
        if (val < 0x80)   return 1;
        if (val < 0x4000) return 2;
        return 3;
    }

    // ── Minimal Base58 encoder / decoder (Bitcoin alphabet) ──

    private static final String BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private static byte[] base58Decode(String input) {
        BigInteger result = BigInteger.ZERO;
        for (char c : input.toCharArray()) {
            int digit = BASE58_ALPHABET.indexOf(c);
            if (digit < 0) throw new IllegalArgumentException("非法 Base58 字符: " + c);
            result = result.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
        }
        // count leading 1s (map to 0x00 bytes)
        int leadingZeros = 0;
        for (char c : input.toCharArray()) {
            if (c == '1') leadingZeros++; else break;
        }
        byte[] decoded = result.toByteArray();
        // remove potential sign byte
        int stripSign = (decoded.length > 0 && decoded[0] == 0) ? 1 : 0;
        byte[] out = new byte[leadingZeros + decoded.length - stripSign];
        System.arraycopy(decoded, stripSign, out, leadingZeros, decoded.length - stripSign);
        return out;
    }

    private static String base58Encode(byte[] input) {
        BigInteger num = new BigInteger(1, input);
        StringBuilder sb = new StringBuilder();
        BigInteger base = BigInteger.valueOf(58);
        while (num.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divRem = num.divideAndRemainder(base);
            num = divRem[0];
            sb.append(BASE58_ALPHABET.charAt(divRem[1].intValue()));
        }
        for (byte b : input) {
            if (b == 0) sb.append('1'); else break;
        }
        return sb.reverse().toString();
    }
}
