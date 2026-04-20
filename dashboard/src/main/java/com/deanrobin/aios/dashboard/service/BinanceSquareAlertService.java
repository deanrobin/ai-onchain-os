package com.deanrobin.aios.dashboard.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 币安广场热度飞书报告。
 *
 * 小时榜：每小时整点（9:00、10:00）报告最近 1 小时 Top10 代币。
 * 日榜  ：每天 08:00 北京时间报告最近 24 小时 Top20 代币。
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class BinanceSquareAlertService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    private final BinanceSquareService squareService;
    private final WebClient.Builder    webClientBuilder;

    @Value("${binance-square.alert-url:${perp.alert-url:}}")
    private String alertUrl;

    public void sendHourlyReport() {
        List<Map<String, Object>> top = squareService.topTokensSince(1, 10);
        if (top.isEmpty()) {
            log.info("📰 币安广场小时榜为空，跳过飞书汇报");
            return;
        }
        String time = LocalDateTime.now(CST).format(FMT);
        StringBuilder sb = new StringBuilder();
        sb.append("🔥 币安广场热度·近 1 小时 Top10 | ").append(time).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━\n");
        appendRanked(sb, top);
        send(sb.toString(), "小时榜");
    }

    public void sendDailyReport() {
        List<Map<String, Object>> top = squareService.topTokensSince(24, 20);
        if (top.isEmpty()) {
            log.info("📰 币安广场日榜为空，跳过飞书汇报");
            return;
        }
        String time = LocalDateTime.now(CST).format(FMT);
        StringBuilder sb = new StringBuilder();
        sb.append("📊 币安广场热度·近 24 小时 Top20 | ").append(time).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━\n");
        appendRanked(sb, top);
        send(sb.toString(), "日榜");
    }

    private void appendRanked(StringBuilder sb, List<Map<String, Object>> list) {
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> d = list.get(i);
            String token = String.valueOf(d.get("token"));
            int score    = ((Number) d.getOrDefault("score",     0)).intValue();
            int likes    = ((Number) d.getOrDefault("likes",     0)).intValue();
            int comments = ((Number) d.getOrDefault("comments",  0)).intValue();
            int posts    = ((Number) d.getOrDefault("postCount", 0)).intValue();
            boolean inBinance = Boolean.TRUE.equals(d.get("inBinance"));
            sb.append(String.format("  %2d. %-10s 分%-5d 👍%-4d 💬%-3d 帖%-2d %s%n",
                    i + 1, token, score, likes, comments, posts,
                    inBinance ? "✅" : "❓"));
        }
    }

    private void send(String text, String tag) {
        if (alertUrl == null || alertUrl.isBlank()) {
            log.warn("⚠️ binance-square.alert-url 未配置，跳过{}汇报", tag);
            return;
        }
        try {
            Map<String, Object> body = Map.of(
                    "msg_type", "text",
                    "content",  Map.of("text", text));
            webClientBuilder.build().post().uri(alertUrl)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve().bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                    .subscribe();
            log.info("📤 币安广场{}飞书汇报已发送", tag);
        } catch (Exception e) {
            log.warn("⚠️ 飞书发送失败: {}", e.getMessage());
        }
    }
}
