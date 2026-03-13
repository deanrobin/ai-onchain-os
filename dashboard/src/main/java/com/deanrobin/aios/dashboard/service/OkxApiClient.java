package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.config.OkxApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;

@Log4j2
@Service
@RequiredArgsConstructor
public class OkxApiClient {

    private final OkxApiConfig cfg;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    // ─── Public GET helper ────────────────────────────────────
    public Map<?, ?> get(String base, String path, Map<String, String> params) {
        String qs = buildQs(params);
        String fullPath = path + (qs.isEmpty() ? "" : "?" + qs);
        String ts = TS_FMT.format(Instant.now());
        String sig = sign(ts, "GET", fullPath, "");

        return WebClient.create(base)
                .get().uri(fullPath)
                .headers(h -> applyHeaders(h, ts, sig))
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorResume(e -> {
                    log.error("OKX GET {} failed: {}", fullPath, e.getMessage());
                    return Mono.just(Map.of("code", "-1", "msg", e.getMessage()));
                })
                .block();
    }

    // ─── Public POST helper ───────────────────────────────────
    public Map<?, ?> post(String base, String path, Object body) {
        String ts = TS_FMT.format(Instant.now());
        String bodyStr = toJson(body);
        String sig = sign(ts, "POST", path, bodyStr);

        return WebClient.create(base)
                .post().uri(path)
                .headers(h -> applyHeaders(h, ts, sig))
                .bodyValue(bodyStr)
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorResume(e -> {
                    log.error("OKX POST {} failed: {}", path, e.getMessage());
                    return Mono.just(Map.of("code", "-1", "msg", e.getMessage()));
                })
                .block();
    }

    // ─── Convenience methods ──────────────────────────────────
    public Map<?, ?> getWww(String path, Map<String, String> params) {
        return get(cfg.getBaseWww(), path, params);
    }

    public Map<?, ?> getWeb3(String path, Map<String, String> params) {
        return get(cfg.getBaseWeb3(), path, params);
    }

    public Map<?, ?> postWeb3(String path, Object body) {
        return post(cfg.getBaseWeb3(), path, body);
    }

    // ─── Auth ─────────────────────────────────────────────────
    private String sign(String ts, String method, String path, String body) {
        try {
            String msg = ts + method + path + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cfg.getApiSecret().getBytes(), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(msg.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException("OKX sign failed", e);
        }
    }

    private void applyHeaders(org.springframework.http.HttpHeaders h,
                               String ts, String sig) {
        h.set("OK-ACCESS-KEY", cfg.getApiKey());
        h.set("OK-ACCESS-SIGN", sig);
        h.set("OK-ACCESS-TIMESTAMP", ts);
        h.set("OK-ACCESS-PASSPHRASE", cfg.getPassphrase());
        h.set("OK-ACCESS-PROJECT", cfg.getApiKey());
        h.set("Content-Type", "application/json");
    }

    private String buildQs(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
