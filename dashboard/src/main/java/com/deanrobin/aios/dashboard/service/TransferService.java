package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.repository.TransferWhitelistRepository;
import com.deanrobin.aios.dashboard.vo.TradeResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 转账执行服务
 *
 * 支持链：BSC (chainId=56)、Solana (chainId=501)
 * 支持代币：native（BNB / SOL）、usdt
 *   - BSC USDT:  0x55d398326f99059fF775485246999027B3197955  (18 decimals)
 *   - SOL USDT:  Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB (6 decimals)
 *
 * 白名单校验：
 *   - BSC 地址 → 转小写后匹配
 *   - SOL 地址 → 原始字符串匹配
 *
 * OKX API 调用：sleep 300-500ms，最多重试 3 次
 * 广播：BSC 走 OKX broadcast API，失败降级 BSC RPC；SOL 走 Solana RPC
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class TransferService {

    // ──────────────────────── CONSTANTS ─────────────────────────
    private static final String BSC_RPC            = "https://bsc-dataseed.binance.org/";
    private static final String SOL_RPC            = "https://api.mainnet-beta.solana.com";
    private static final long   BSC_CHAIN_ID       = 56L;
    private static final String BSC_USDT           = "0x55d398326f99059fF775485246999027B3197955";
    private static final String SOL_USDT_MINT      = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";
    private static final String SOL_TOKEN_PROGRAM  = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    private static final String SOL_ATA_PROGRAM    = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJe8bv";
    private static final String SOL_SYSTEM_PROGRAM = "11111111111111111111111111111111";

    // ERC20 selectors
    private static final String ERC20_TRANSFER_SIG  = "0xa9059cbb"; // transfer(address,uint256)
    private static final String ERC20_BALANCEOF_SIG  = "0x70a08231"; // balanceOf(address)

    private static final int    MAX_RETRIES     = 3;
    private static final int    OKX_SLEEP_MIN   = 300;
    private static final int    OKX_SLEEP_MAX   = 500;
    private static final long   BCAST_RETRY_MS  = 500L;

    private final OkxApiClient             okxApiClient;
    private final TransferWhitelistRepository whitelistRepo;
    private final ObjectMapper             objectMapper = new ObjectMapper();

    // ─────────────────────────── PUBLIC API ────────────────────────

    /**
     * 执行转账
     * @param chain     "sol" 或 "bsc"
     * @param toAddress 收款地址
     * @param tokenType "native" 或 "usdt"
     * @param amount    金额（主币单位，如 0.5 SOL；或 USDT 数量，如 100）
     */
    public TradeResult executeTransfer(String chain, String toAddress, String tokenType, String amount) {
        try {
            chain     = chain.trim().toLowerCase();
            tokenType = tokenType.trim().toLowerCase();
            toAddress = toAddress.trim();
            amount    = amount.trim();

            // ── 1. 白名单校验 ──
            boolean whitelisted = switch (chain) {
                case "bsc" -> whitelistRepo.findByAddress(toAddress.toLowerCase()).isPresent();
                case "sol" -> whitelistRepo.findByAddress(toAddress).isPresent();
                default    -> false;
            };
            if (!whitelisted) {
                return TradeResult.error("收款地址不在白名单中，转账被拒绝: " + toAddress);
            }

            return switch (chain) {
                case "bsc" -> executeBscTransfer(toAddress, tokenType, amount);
                case "sol" -> executeSolTransfer(toAddress, tokenType, amount);
                default    -> TradeResult.error("不支持的链: " + chain + "（仅支持 bsc / sol）");
            };
        } catch (Exception e) {
            log.error("Transfer failed chain={} to={} token={} amount={}", chain, toAddress, tokenType, amount, e);
            return TradeResult.error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    // ══════════════════════════ BSC ════════════════════════════════

    private TradeResult executeBscTransfer(String toAddress, String tokenType, String amount) throws Exception {
        String rawKey    = loadEnvKey("WALLET_PRIVATE_KEY_EVM");
        Credentials cred = Credentials.create(rawKey);
        String from      = cred.getAddress();
        Web3j web3j      = Web3j.build(new HttpService(BSC_RPC));

        log.info("BSC transfer: from={} to={} token={} amount={}", from, toAddress, tokenType, amount);

        // ── 金额转换 ──
        BigInteger amountWei;
        if ("usdt".equals(tokenType)) {
            // BSC USDT: 18 decimals
            amountWei = new BigDecimal(amount).multiply(BigDecimal.TEN.pow(18)).toBigInteger();
        } else {
            // BNB: 18 decimals
            amountWei = new BigDecimal(amount).multiply(BigDecimal.TEN.pow(18)).toBigInteger();
        }

        // ── 余额校验 ──
        if ("usdt".equals(tokenType)) {
            BigInteger usdtBalance = getBscTokenBalance(web3j, from, BSC_USDT);
            if (usdtBalance.compareTo(amountWei) < 0) {
                BigDecimal humanBalance = new BigDecimal(usdtBalance).divide(BigDecimal.TEN.pow(18), 6, java.math.RoundingMode.DOWN);
                return TradeResult.error("USDT 余额不足，当前余额: " + humanBalance.toPlainString() + " USDT");
            }
        } else {
            EthGetBalance balResp = web3j.ethGetBalance(from, DefaultBlockParameterName.LATEST).send();
            BigInteger bnbBalance = balResp.getBalance();
            if (bnbBalance.compareTo(amountWei) < 0) {
                BigDecimal human = new BigDecimal(bnbBalance).divide(BigDecimal.TEN.pow(18), 6, java.math.RoundingMode.DOWN);
                return TradeResult.error("BNB 余额不足，当前余额: " + human.toPlainString() + " BNB");
            }
        }

        // ── Gas & Nonce ──
        EthGetTransactionCount nonceResp = web3j.ethGetTransactionCount(from, DefaultBlockParameterName.LATEST).send();
        BigInteger nonce    = nonceResp.getTransactionCount();
        EthGasPrice gasPriceResp = web3j.ethGasPrice().send();
        BigInteger  gasPrice = gasPriceResp.getGasPrice();
        // 加 10% 溢价，避免 gasPrice 过低
        gasPrice = gasPrice.multiply(BigInteger.valueOf(110)).divide(BigInteger.valueOf(100));

        // ── 构建交易 ──
        RawTransaction rawTx;
        if ("usdt".equals(tokenType)) {
            // ERC20 transfer calldata
            String data = buildErc20TransferData(toAddress, amountWei);
            BigInteger gasLimit = BigInteger.valueOf(100_000);
            rawTx = RawTransaction.createTransaction(nonce, gasPrice, gasLimit, BSC_USDT, BigInteger.ZERO, data);
        } else {
            BigInteger gasLimit = BigInteger.valueOf(21_000);
            rawTx = RawTransaction.createEtherTransaction(nonce, gasPrice, gasLimit, toAddress, amountWei);
        }
        log.info("BSC nonce={} gasPrice={} tokenType={}", nonce, gasPrice, tokenType);

        // ── 签名 ──
        byte[] signed   = TransactionEncoder.signMessage(rawTx, BSC_CHAIN_ID, cred);
        String hexSigned = Numeric.toHexString(signed);

        // ── 广播（OKX → 失败降级 BSC RPC）──
        return broadcastBsc(web3j, hexSigned);
    }

    /** 构造 ERC20 transfer(address,uint256) calldata */
    private String buildErc20TransferData(String toAddress, BigInteger amount) {
        // selector(4) + address_padded(32) + amount_padded(32)
        StringBuilder sb = new StringBuilder(ERC20_TRANSFER_SIG);
        // address: 12-byte zero-pad + 20-byte address
        String cleanAddr = Numeric.cleanHexPrefix(toAddress).toLowerCase();
        sb.append("000000000000000000000000").append(cleanAddr);
        // amount: 32-byte big-endian
        String amtHex = amount.toString(16);
        String amtPadded = String.format("%064s", amtHex).replace(' ', '0');
        sb.append(amtPadded);
        return sb.toString();
    }

    /** 调用 eth_call 获取 ERC20 balanceOf */
    private BigInteger getBscTokenBalance(Web3j web3j, String walletAddr, String tokenAddr) throws Exception {
        String data = ERC20_BALANCEOF_SIG + "000000000000000000000000"
                + Numeric.cleanHexPrefix(walletAddr).toLowerCase();
        Transaction call = Transaction.createEthCallTransaction(walletAddr, tokenAddr, data);
        EthCall resp = web3j.ethCall(call, DefaultBlockParameterName.LATEST).send();
        String hex = resp.getValue();
        if (hex == null || hex.equals("0x")) return BigInteger.ZERO;
        return Numeric.decodeQuantity(hex);
    }

    /** 广播 BSC 交易，优先走 OKX broadcast API，失败降级 BSC RPC */
    private TradeResult broadcastBsc(Web3j web3j, String hexSigned) throws Exception {
        // ── 尝试 OKX broadcast ──
        try {
            TradeResult okxResult = broadcastViaOkx(hexSigned, "56");
            if (okxResult.isSuccess()) return okxResult;
            log.warn("OKX broadcast failed, fallback to BSC RPC: {}", okxResult.getErrorMsg());
        } catch (Exception e) {
            log.warn("OKX broadcast exception, fallback to BSC RPC: {}", e.getMessage());
        }
        // ── 降级：BSC RPC ──
        Exception lastErr = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                EthSendTransaction resp = web3j.ethSendRawTransaction(hexSigned).send();
                String hash = resp.getTransactionHash();
                if (hash != null && !hash.isEmpty()) {
                    log.info("BSC RPC broadcast success txHash={}", hash);
                    return TradeResult.success(hash);
                }
                if (resp.getError() != null) {
                    lastErr = new RuntimeException("BSC RPC: " + resp.getError().getMessage());
                    log.warn("BSC RPC broadcast attempt {} error: {}", i + 1, resp.getError().getMessage());
                }
            } catch (Exception e) {
                lastErr = e;
                log.warn("BSC RPC broadcast attempt {} exception: {}", i + 1, e.getMessage());
            }
            if (i < MAX_RETRIES - 1) Thread.sleep(BCAST_RETRY_MS);
        }
        throw lastErr != null ? lastErr : new RuntimeException("BSC 广播失败（已重试 3 次）");
    }

    // ══════════════════════════ SOLANA ══════════════════════════════

    private TradeResult executeSolTransfer(String toAddress, String tokenType, String amount) throws Exception {
        String rawKey = loadEnvKey("WALLET_PRIVATE_KEY_SOL");
        byte[] keypair = base58Decode(rawKey.trim());
        if (keypair.length < 32) throw new IllegalArgumentException("SOL 私钥长度不足");
        byte[] seed       = Arrays.copyOf(keypair, 32);
        Ed25519PrivateKeyParameters privKey = new Ed25519PrivateKeyParameters(seed, 0);
        byte[] fromPubkey = privKey.generatePublicKey().getEncoded();
        String fromAddr   = base58Encode(fromPubkey);
        log.info("SOL transfer: from={} to={} token={} amount={}", fromAddr, toAddress, tokenType, amount);

        // ── 金额转换 ──
        BigInteger units;
        if ("usdt".equals(tokenType)) {
            // SOL USDT: 6 decimals
            units = new BigDecimal(amount).multiply(BigDecimal.TEN.pow(6)).toBigInteger();
        } else {
            // SOL: 9 decimals (lamports)
            units = new BigDecimal(amount).multiply(BigDecimal.valueOf(1_000_000_000L)).toBigInteger();
        }

        // ── 余额校验 ──
        if ("usdt".equals(tokenType)) {
            BigInteger usdtBal = getSolTokenBalance(fromAddr, SOL_USDT_MINT);
            if (usdtBal.compareTo(units) < 0) {
                BigDecimal human = new BigDecimal(usdtBal).divide(BigDecimal.TEN.pow(6), 6, java.math.RoundingMode.DOWN);
                return TradeResult.error("SOL USDT 余额不足，当前: " + human.toPlainString() + " USDT");
            }
        } else {
            BigInteger lamBal = getSolNativeBalance(fromAddr);
            // 预留 0.001 SOL 作为 gas
            BigInteger minReserve = BigInteger.valueOf(1_000_000L);
            if (lamBal.subtract(minReserve).compareTo(units) < 0) {
                BigDecimal human = new BigDecimal(lamBal).divide(BigDecimal.valueOf(1_000_000_000L), 6, java.math.RoundingMode.DOWN);
                return TradeResult.error("SOL 余额不足，当前: " + human.toPlainString() + " SOL（含 Gas 预留）");
            }
        }

        // ── 获取 recent blockhash ──
        String blockhash = getSolRecentBlockhash();
        log.info("SOL blockhash={}", blockhash);

        // ── 构建交易 ──
        byte[] toPubkey = base58Decode(toAddress);
        byte[] txBytes;
        if ("usdt".equals(tokenType)) {
            txBytes = buildSolSplTransfer(fromPubkey, toPubkey, units, blockhash);
        } else {
            txBytes = buildSolNativeTransfer(fromPubkey, toPubkey, units, blockhash);
        }

        // ── 签名 ──
        byte[] signedTx = signSolanaTransaction(txBytes, privKey);
        String signedBase64 = Base64.getEncoder().encodeToString(signedTx);

        // ── 广播 ──
        return broadcastSol(signedBase64);
    }

    /** SOL 原生转账交易 */
    private byte[] buildSolNativeTransfer(byte[] from, byte[] to, BigInteger lamports, String blockhash) throws Exception {
        byte[] systemProgram = base58Decode(SOL_SYSTEM_PROGRAM);
        // Accounts: [from(signer,writable), to(writable), SystemProgram(readonly)]
        List<byte[]> accounts = List.of(from, to, systemProgram);
        // Header: 1 signer, 0 readonly signed, 1 readonly unsigned
        byte[] header = {1, 0, 1};
        // Instruction data: System Transfer = [2,0,0,0] + lamports LE u64
        byte[] instData = buildSystemTransferData(lamports);
        // Instruction: programIdIndex=2, accounts=[0,1], data
        byte[] instruction = buildInstruction(2, new int[]{0, 1}, instData);
        return buildSolTransaction(accounts, header, base58Decode(blockhash), List.of(instruction));
    }

    /** SPL Token 转账交易（USDT）*/
    private byte[] buildSolSplTransfer(byte[] from, byte[] to, BigInteger amount, String blockhash) throws Exception {
        byte[] tokenProgramId = base58Decode(SOL_TOKEN_PROGRAM);
        byte[] mintPubkey     = base58Decode(SOL_USDT_MINT);

        // 推导 ATA 地址
        byte[] fromAta = deriveAta(from, mintPubkey);
        byte[] toAta   = deriveAta(to, mintPubkey);

        log.info("SOL SPL transfer fromAta={} toAta={}", base58Encode(fromAta), base58Encode(toAta));

        // Accounts: [fromAta(writable), toAta(writable), from(signer,writable), TokenProgram(readonly)]
        List<byte[]> accounts = List.of(fromAta, toAta, from, tokenProgramId);
        // Header: 1 signer, 0 readonly signed, 1 readonly unsigned
        byte[] header = {1, 0, 1};
        // Instruction data: Transfer = [3] + amount LE u64
        byte[] instData = buildSplTransferData(amount);
        // Instruction: programIdIndex=3, accounts=[0,1,2], data
        byte[] instruction = buildInstruction(3, new int[]{0, 1, 2}, instData);
        return buildSolTransaction(accounts, header, base58Decode(blockhash), List.of(instruction));
    }

    /** System Program Transfer instruction data: [2,0,0,0] + lamports as LE u64 */
    private static byte[] buildSystemTransferData(BigInteger lamports) {
        ByteBuffer buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(2); // instruction index = 2 (Transfer)
        buf.putLong(lamports.longValueExact());
        return buf.array();
    }

    /** SPL Token Transfer instruction data: [3] + amount as LE u64 */
    private static byte[] buildSplTransferData(BigInteger amount) {
        ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 3); // instruction index = 3 (Transfer)
        buf.putLong(amount.longValueExact());
        return buf.array();
    }

    /** 组装 Solana 交易字节 */
    private static byte[] buildSolTransaction(List<byte[]> accounts, byte[] header,
                                               byte[] blockhash, List<byte[]> instructions) {
        // ── Message ──
        ByteBuffer msg = ByteBuffer.allocate(4096);
        // header (3 bytes)
        msg.put(header);
        // accounts
        msg.put(encodeCompactU16(accounts.size()));
        for (byte[] acc : accounts) msg.put(acc);
        // recent blockhash (32 bytes)
        msg.put(blockhash);
        // instructions
        msg.put(encodeCompactU16(instructions.size()));
        for (byte[] inst : instructions) msg.put(inst);
        int msgLen = msg.position();
        byte[] message = Arrays.copyOf(msg.array(), msgLen);

        // ── Full tx: compact-u16(1) + 64 zero bytes (sig placeholder) + message ──
        byte[] sigPlaceholder = new byte[64];
        ByteBuffer tx = ByteBuffer.allocate(1 + 64 + msgLen);
        tx.put((byte) 0x01);       // compact-u16: 1 signature
        tx.put(sigPlaceholder);
        tx.put(message);
        return tx.array();
    }

    /** 构建指令字节 */
    private static byte[] buildInstruction(int programIdIndex, int[] accountIndexes, byte[] data) {
        ByteBuffer buf = ByteBuffer.allocate(256);
        buf.put((byte) programIdIndex);
        buf.put(encodeCompactU16(accountIndexes.length));
        for (int idx : accountIndexes) buf.put((byte) idx);
        buf.put(encodeCompactU16(data.length));
        buf.put(data);
        int len = buf.position();
        return Arrays.copyOf(buf.array(), len);
    }

    /** Encode compact-u16 (Solana wire format) */
    private static byte[] encodeCompactU16(int val) {
        if (val < 0x80) return new byte[]{(byte) val};
        if (val < 0x4000) return new byte[]{(byte) ((val & 0x7F) | 0x80), (byte) (val >> 7)};
        return new byte[]{(byte) ((val & 0x7F) | 0x80), (byte) (((val >> 7) & 0x7F) | 0x80), (byte) (val >> 14)};
    }

    /** 对 Solana 交易签名（替换首个签名槽位） */
    private byte[] signSolanaTransaction(byte[] txBytes, Ed25519PrivateKeyParameters privKey) {
        // txBytes: [0x01][64-byte sig slot][message...]
        byte[] message = Arrays.copyOfRange(txBytes, 65, txBytes.length);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privKey);
        signer.update(message, 0, message.length);
        byte[] signature = signer.generateSignature();
        byte[] result = txBytes.clone();
        System.arraycopy(signature, 0, result, 1, 64);
        return result;
    }

    // ── Solana RPC helpers ──

    private BigInteger getSolNativeBalance(String address) throws Exception {
        String reqBody = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "getBalance",
                "params", List.of(address)));
        String resp = solRpcPost(reqBody);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(resp, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) parsed.get("result");
        Number value = (Number) result.get("value");
        return BigInteger.valueOf(value.longValue());
    }

    private BigInteger getSolTokenBalance(String walletAddr, String mintAddr) throws Exception {
        String reqBody = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "getTokenAccountsByOwner",
                "params", List.of(
                        walletAddr,
                        Map.of("mint", mintAddr),
                        Map.of("encoding", "jsonParsed"))));
        String resp = solRpcPost(reqBody);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(resp, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) parsed.get("result");
        if (result == null) return BigInteger.ZERO;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> value = (List<Map<String, Object>>) result.get("value");
        if (value == null || value.isEmpty()) return BigInteger.ZERO;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> acct = (Map<String, Object>) value.get(0).get("account");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) acct.get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> parsedData = (Map<String, Object>) data.get("parsed");
            @SuppressWarnings("unchecked")
            Map<String, Object> info = (Map<String, Object>) parsedData.get("info");
            @SuppressWarnings("unchecked")
            Map<String, Object> tokenAmount = (Map<String, Object>) info.get("tokenAmount");
            String amountStr = tokenAmount.get("amount").toString();
            return new BigInteger(amountStr);
        } catch (Exception e) {
            log.warn("Failed to parse SOL token balance: {}", e.getMessage());
            return BigInteger.ZERO;
        }
    }

    private String getSolRecentBlockhash() throws Exception {
        String reqBody = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "getLatestBlockhash",
                "params", List.of(Map.of("commitment", "confirmed"))));
        String resp = solRpcPost(reqBody);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(resp, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) parsed.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.get("value");
        return value.get("blockhash").toString();
    }

    private String solRpcPost(String body) {
        return WebClient.create(SOL_RPC)
                .post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private TradeResult broadcastSol(String signedBase64) throws Exception {
        String reqBody = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "sendTransaction",
                "params", List.of(signedBase64, Map.of(
                        "encoding", "base64",
                        "preflightCommitment", "confirmed"
                ))));
        Exception lastErr = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                String respStr = solRpcPost(reqBody);
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

    // ══════════════════════════ OKX BROADCAST ═══════════════════════

    /**
     * 通过 OKX API 广播已签名交易
     * POST /api/v5/dex/pre-transaction/broadcast-transaction
     */
    private TradeResult broadcastViaOkx(String signedTx, String chainIndex) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("signedTx", signedTx);
        body.put("chainIndex", chainIndex);

        Exception lastErr = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                okxSleep(); // 300-500ms 间隔
                Map<?, ?> resp = okxApiClient.postWeb3("/api/v5/dex/pre-transaction/broadcast-transaction", body);
                String code = resp.get("code") != null ? resp.get("code").toString() : "-1";
                if ("0".equals(code)) {
                    Object dataObj = resp.get("data");
                    if (dataObj instanceof List<?> list && !list.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> item = (Map<String, Object>) list.get(0);
                        String txHash = item.get("txHash") != null ? item.get("txHash").toString() : null;
                        if (txHash == null) txHash = item.get("orderId") != null ? item.get("orderId").toString() : null;
                        if (txHash != null && !txHash.isBlank()) {
                            log.info("OKX broadcast success txHash/orderId={}", txHash);
                            return TradeResult.success(txHash);
                        }
                    }
                }
                lastErr = new RuntimeException("OKX broadcast code=" + code + " msg=" + resp.get("msg"));
                log.warn("OKX broadcast attempt {} failed: code={} msg={}", i + 1, code, resp.get("msg"));
            } catch (Exception e) {
                lastErr = e;
                log.warn("OKX broadcast attempt {} exception: {}", i + 1, e.getMessage());
            }
            if (i < MAX_RETRIES - 1) Thread.sleep(BCAST_RETRY_MS);
        }
        throw lastErr != null ? lastErr : new RuntimeException("OKX broadcast 失败（已重试 3 次）");
    }

    /** OKX API 调用间隔：随机 300-500ms */
    private static void okxSleep() throws InterruptedException {
        long ms = ThreadLocalRandom.current().nextLong(OKX_SLEEP_MIN, OKX_SLEEP_MAX + 1);
        Thread.sleep(ms);
    }

    // ══════════════════════════ ATA DERIVATION ════════════════════════

    /**
     * 推导 Associated Token Account 地址
     * seeds = [ownerPubkey, tokenProgramId, mintPubkey]
     * program = associatedTokenProgramId
     */
    private byte[] deriveAta(byte[] ownerPubkey, byte[] mintPubkey) throws Exception {
        byte[] tokenProgramId = base58Decode(SOL_TOKEN_PROGRAM);
        byte[] ataProgramId   = base58Decode(SOL_ATA_PROGRAM);
        List<byte[]> seeds = List.of(ownerPubkey, tokenProgramId, mintPubkey);
        return findProgramAddress(seeds, ataProgramId);
    }

    /**
     * Solana PDA 推导：SHA256(seed1||seed2||...||nonce||programId||"ProgramDerivedAddress")
     * 找到第一个不在 Ed25519 曲线上的结果
     */
    private static byte[] findProgramAddress(List<byte[]> seeds, byte[] programId) throws Exception {
        for (int nonce = 255; nonce >= 0; nonce--) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] seed : seeds) digest.update(seed);
            digest.update((byte) nonce);
            digest.update(programId);
            digest.update("ProgramDerivedAddress".getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            if (!isOnEd25519Curve(hash)) {
                return hash;
            }
        }
        throw new RuntimeException("无法找到有效 PDA");
    }

    /** 检测 32-byte 值是否为有效的 Ed25519 曲线点 */
    private static boolean isOnEd25519Curve(byte[] bytes) {
        try {
            new Ed25519PublicKeyParameters(bytes, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ══════════════════════════ KEY LOADING ══════════════════════════

    private String loadEnvKey(String envName) {
        String val = System.getenv(envName);
        if (val == null || val.isBlank()) throw new IllegalStateException("环境变量 " + envName + " 未设置");
        val = val.trim();
        return val.startsWith("ENC:") ? decryptAes(val.substring(4)) : val;
    }

    private String decryptAes(String payload) {
        int colon = payload.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException("加密私钥格式错误");
        try {
            byte[] iv     = Base64.getDecoder().decode(payload.substring(0, colon));
            byte[] cipher = Base64.getDecoder().decode(payload.substring(colon + 1));
            String pwd    = System.getenv("WALLET_KEY_PASSWORD");
            if (pwd == null || pwd.isBlank()) throw new IllegalStateException("WALLET_KEY_PASSWORD 未设置");
            byte[] key = MessageDigest.getInstance("SHA-256").digest(pwd.getBytes(StandardCharsets.UTF_8));
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return new String(c.doFinal(cipher), StandardCharsets.UTF_8).trim();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("AES 解密失败: " + e.getMessage(), e);
        }
    }

    // ══════════════════════════ BASE58 ════════════════════════════════

    private static final String B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private static byte[] base58Decode(String input) {
        BigInteger result = BigInteger.ZERO;
        for (char c : input.toCharArray()) {
            int d = B58.indexOf(c);
            if (d < 0) throw new IllegalArgumentException("非法 Base58 字符: " + c);
            result = result.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(d));
        }
        int leadingZeros = 0;
        for (char c : input.toCharArray()) { if (c == '1') leadingZeros++; else break; }
        byte[] decoded = result.toByteArray();
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
            BigInteger[] dr = num.divideAndRemainder(base);
            num = dr[0]; sb.append(B58.charAt(dr[1].intValue()));
        }
        for (byte b : input) { if (b == 0) sb.append('1'); else break; }
        return sb.reverse().toString();
    }
}
