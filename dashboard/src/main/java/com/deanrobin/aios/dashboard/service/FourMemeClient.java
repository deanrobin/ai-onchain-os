package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.model.FourMemeToken;
import com.deanrobin.aios.dashboard.repository.FourMemeTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
@Service
@RequiredArgsConstructor
public class FourMemeClient {

    private static final String WSS_URL  = "wss://ws.four.meme/ws";
    private static final String SUBSCRIBE = "{\"method\":\"SUBSCRIBE\",\"params\":\"NEW-NOR@TOKEN_LIST_EVENT@0\"}";
    private static final String PING      = "{\"event\":\"ping\"}";

    private final FourMemeTokenRepository fourMemeRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile WebSocket webSocket;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        connect();
    }

    private void connect() {
        try {
            log.info("🔌 FourMeme WSS 连接中... {}", WSS_URL);
            HttpClient client = HttpClient.newHttpClient();
            webSocket = client.newWebSocketBuilder()
                    .header("Origin", "https://four.meme")
                    .buildAsync(URI.create(WSS_URL), new WsListener())
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("⚠️ FourMeme WSS 连接失败: {}，30s 后重试", e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        connected.set(false);
        scheduler.schedule(this::connect, 30, TimeUnit.SECONDS);
    }

    /** 每 30 秒发一次 ping 保持连接 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 35_000)
    public void heartbeat() {
        if (connected.get() && webSocket != null) {
            try { webSocket.sendText(PING, true); }
            catch (Exception e) { log.debug("FourMeme ping 失败: {}", e.getMessage()); }
        }
    }

    /** 每 5 分钟清理旧数据，保留最近 10 万条 */
    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        try { fourMemeRepo.retainLatest(100_000); }
        catch (Exception e) { log.warn("four_meme_token 清理失败: {}", e.getMessage()); }
    }

    private class WsListener implements WebSocket.Listener {

        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            connected.set(true);
            log.info("✅ FourMeme WSS 已连接");
            ws.sendText(SUBSCRIBE, true);
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                handleMessage(msg);
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("⚠️ FourMeme WSS 错误: {}", error.getMessage());
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.warn("🔌 FourMeme WSS 断开 code={} reason={}", statusCode, reason);
            scheduleReconnect();
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void handleMessage(String raw) {
        if (raw.contains("\"pong\"") || raw.contains("\"ping\"")) return;
        try {
            Map<String, Object> msg = objectMapper.readValue(raw, Map.class);
            Object dataObj = msg.get("data");
            // data 是直接的 token 数组（不是嵌套 {list:[...]}）
            if (!(dataObj instanceof List)) return;

            List<Map<String, Object>> tokens = (List<Map<String, Object>>) dataObj;
            int saved = 0;
            for (Map<String, Object> item : tokens) {
                String addr = str(item, "tokenAddress");
                if (addr == null || addr.isBlank()) continue;
                if (fourMemeRepo.existsByTokenAddress(addr)) continue;

                FourMemeToken t = new FourMemeToken();
                t.setTokenAddress(addr);
                t.setName(str(item, "name"));
                t.setShortName(str(item, "shortName"));
                t.setCreator(str(item, "userAddress"));
                t.setImg(str(item, "img"));
                t.setReceivedAt(LocalDateTime.now());

                Object tid = item.get("tokenId");
                if (tid instanceof Number n) t.setTokenId(n.longValue());

                Object cd = item.get("createDate");
                if (cd instanceof Number n) t.setCreateDate(n.longValue());

                Object capObj = item.get("cap");
                if (capObj != null) {
                    try { t.setCapBnb(new java.math.BigDecimal(capObj.toString())); }
                    catch (Exception ignored) {}
                }
                Object prg = item.get("progress");
                if (prg != null) {
                    try { t.setProgress(new java.math.BigDecimal(prg.toString())); }
                    catch (Exception ignored) {}
                }
                Object pr = item.get("price");
                if (pr != null) {
                    try { t.setPrice(new java.math.BigDecimal(pr.toString())); }
                    catch (Exception ignored) {}
                }
                Object hold = item.get("hold");
                if (hold instanceof Number n) t.setHold(n.intValue());

                try {
                    fourMemeRepo.save(t);
                    saved++;
                } catch (DataIntegrityViolationException ignored) {}
            }
            // 新币不打印，减少日志噪音
        } catch (Exception e) {
            log.debug("FourMeme message 解析失败: {}", e.getMessage());
        }
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null || "null".equals(v.toString()) ? null : v.toString().trim();
    }

    public boolean isConnected() { return connected.get(); }
}
