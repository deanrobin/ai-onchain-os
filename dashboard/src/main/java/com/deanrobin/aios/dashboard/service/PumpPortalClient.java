package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.model.PumpToken;
import com.deanrobin.aios.dashboard.repository.PumpTokenRepository;
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
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
@Service
@RequiredArgsConstructor
public class PumpPortalClient {

    private static final String WSS_URL = "wss://pumpportal.fun/api/data";

    private final PumpTokenRepository pumpTokenRepo;
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
            log.info("🔌 PumpPortal WSS 连接中... {}", WSS_URL);
            HttpClient client = HttpClient.newHttpClient();
            webSocket = client.newWebSocketBuilder()
                    .buildAsync(URI.create(WSS_URL), new WsListener())
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("⚠️ PumpPortal WSS 连接失败: {}，30s 后重试", e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        connected.set(false);
        scheduler.schedule(this::connect, 30, TimeUnit.SECONDS);
    }

    /** 每 500 条清一次旧数据，只保留最近 2000 条 */
    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        try {
            pumpTokenRepo.retainLatest(2000);
        } catch (Exception e) {
            log.warn("pump_token 清理失败: {}", e.getMessage());
        }
    }

    private class WsListener implements WebSocket.Listener {

        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            connected.set(true);
            log.info("✅ PumpPortal WSS 已连接");
            ws.sendText("{\"method\":\"subscribeNewToken\"}", true);
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
            log.warn("⚠️ PumpPortal WSS 错误: {}", error.getMessage());
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.warn("🔌 PumpPortal WSS 断开 code={} reason={}", statusCode, reason);
            scheduleReconnect();
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void handleMessage(String raw) {
        try {
            Map<String, Object> data = objectMapper.readValue(raw, Map.class);
            String mint = str(data, "mint");
            if (mint == null || mint.isBlank()) return;
            if (pumpTokenRepo.existsByMint(mint)) return;

            PumpToken t = new PumpToken();
            t.setMint(mint);
            t.setName(str(data, "name"));
            t.setSymbol(str(data, "symbol"));
            t.setDescription(str(data, "description"));
            t.setImageUri(str(data, "image_uri"));
            t.setTwitter(str(data, "twitter"));
            t.setTelegram(str(data, "telegram"));
            t.setWebsite(str(data, "website"));
            t.setCreator(str(data, "creator"));
            t.setReceivedAt(LocalDateTime.now());

            Object ts = data.get("created_timestamp");
            if (ts instanceof Number n) t.setCreatedTimestamp(n.longValue());

            Object mc = data.get("usd_market_cap");
            if (mc instanceof Number n) t.setUsdMarketCap(new java.math.BigDecimal(n.toString()));

            pumpTokenRepo.save(t);
            log.info("🆕 新币 {} ({}) mint={}", t.getName(), t.getSymbol(), mint.substring(0, 10));
        } catch (DataIntegrityViolationException ignored) {
            // 并发重复写入，忽略
        } catch (Exception e) {
            log.debug("pump message 解析失败: {}", e.getMessage());
        }
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null || "null".equals(v.toString()) ? null : v.toString().trim();
    }

    public boolean isConnected() { return connected.get(); }
}
