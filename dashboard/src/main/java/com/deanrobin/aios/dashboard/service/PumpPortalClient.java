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
            pumpTokenRepo.retainLatest(100_000);
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
            // 只处理新建交易
            String txType = str(data, "txType");
            if (txType != null && !"create".equals(txType)) return;
            if (pumpTokenRepo.existsByMint(mint)) return;

            PumpToken t = new PumpToken();
            t.setMint(mint);
            t.setName(str(data, "name"));
            t.setSymbol(str(data, "symbol"));
            // pumpportal WSS 真实字段
            t.setCreator(str(data, "traderPublicKey"));
            t.setImageUri(str(data, "uri"));         // metadata URI
            t.setReceivedAt(LocalDateTime.now());

            // marketCapSol
            Object mcSol = data.get("marketCapSol");
            if (mcSol instanceof Number n) {
                java.math.BigDecimal mcVal = new java.math.BigDecimal(n.toString());
                t.setMarketCapSol(mcVal);
                // usd_market_cap = marketCapSol × SOL价格（从 price_ticker 取，取不到则 null）
                try {
                    var solPrice = pumpTokenRepo.findSolPrice();
                    if (solPrice != null) t.setUsdMarketCap(mcVal.multiply(solPrice));
                } catch (Exception ignored) {}
            }

            // vSolInBondingCurve → 计算进度
            // pump.fun: 虚拟基础 = 30 SOL, 完成阈值 = 85 SOL → 进度 = (vSol-30)/55*100
            Object vSol = data.get("vSolInBondingCurve");
            if (vSol instanceof Number n) {
                java.math.BigDecimal vSolVal = new java.math.BigDecimal(n.toString())
                        .divide(java.math.BigDecimal.valueOf(1_000_000_000L), 9, java.math.RoundingMode.HALF_UP);
                t.setVSolInCurve(vSolVal);
                // 进度 = (vSol - 30) / 55 * 100，限 [0, 100]
                java.math.BigDecimal prg = vSolVal.subtract(java.math.BigDecimal.valueOf(30))
                        .divide(java.math.BigDecimal.valueOf(55), 4, java.math.RoundingMode.HALF_UP)
                        .multiply(java.math.BigDecimal.valueOf(100));
                if (prg.compareTo(java.math.BigDecimal.ZERO) < 0) prg = java.math.BigDecimal.ZERO;
                if (prg.compareTo(java.math.BigDecimal.valueOf(100)) > 0) prg = java.math.BigDecimal.valueOf(100);
                t.setProgress(prg);
            }

            // initialBuy (SOL)
            Object iBuy = data.get("solAmount");
            if (iBuy instanceof Number n) {
                t.setInitialBuy(new java.math.BigDecimal(n.toString())
                        .divide(java.math.BigDecimal.valueOf(1_000_000_000L), 9, java.math.RoundingMode.HALF_UP));
            }

            pumpTokenRepo.save(t);
            log.info("🆕 新币 {} ({}) mcSol={} prg={}%",
                    t.getName(), t.getSymbol(),
                    t.getMarketCapSol() != null ? t.getMarketCapSol().setScale(1, java.math.RoundingMode.HALF_UP) : "?",
                    t.getProgress() != null ? t.getProgress().setScale(1, java.math.RoundingMode.HALF_UP) : "?");
        } catch (DataIntegrityViolationException ignored) {
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
