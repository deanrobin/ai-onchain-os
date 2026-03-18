package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.util.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 启动后读取 /root/.key，解密私钥并脱敏打印。
 * 全程不阻塞主程序，所有异常均被 catch 处理。
 */
@Service
public class KeyLoaderService {

    private static final Logger log = LoggerFactory.getLogger(KeyLoaderService.class);
    private static final String KEY_FILE = "/root/.xkey";

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void loadKey() {
        try {
            // 1. 读取文件
            Path path = Path.of(KEY_FILE);
            if (!Files.exists(path)) {
                log.info("🔑 [KeyLoader] key file not found: {}", KEY_FILE);
                return;
            }

            List<String> lines = Files.readAllLines(path);
            // 加密内容在第二行（index=1），由 /encrypt 写入
            String encryptedLine = lines.size() >= 2 ? lines.get(1).trim() : "";

            if (encryptedLine.isEmpty()) {
                log.info("🔑 [KeyLoader] key file exists but encrypted content is empty (line 2)");
                return;
            }

            // 2. 取解密密码（来自启动脚本的环境变量）
            String pass = System.getenv("KEY_DECRYPT_PASS");
            if (pass == null || pass.isBlank()) {
                log.warn("🔑 [KeyLoader] KEY_DECRYPT_PASS not set, skipping decryption");
                return;
            }

            // 3. 解密
            String plaintext;
            try {
                plaintext = CryptoUtil.decrypt(encryptedLine, pass);
            } catch (Exception e) {
                log.warn("🔑 [KeyLoader] decryption failed: {}", e.getClass().getSimpleName());
                return;
            }

            // 4. 脱敏打印：前2位 + *** + 后4位
            log.info("🔑 [KeyLoader] key loaded: {}", CryptoUtil.mask(plaintext));

        } catch (Exception e) {
            // 任何意外都不影响程序
            log.warn("🔑 [KeyLoader] unexpected error: {}", e.getClass().getSimpleName());
        }
    }
}
