package com.deanrobin.aios.dashboard.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import com.deanrobin.aios.dashboard.util.CryptoUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Controller
@RequestMapping("/encrypt")
public class EncryptController {

    // 只记录"有调用"，不记录任何参数或内容
    private static final Logger log = LoggerFactory.getLogger(EncryptController.class);



    @GetMapping
    public String page() {
        log.info("encrypt page accessed");
        return "encrypt";
    }

    @PostMapping
    public String doEncrypt(
            @RequestParam String filePath,
            @RequestParam String password,
            Model model) {

        log.info("encrypt called");   // ← 只记这一行，无任何敏感信息

        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                model.addAttribute("error", "File not found");
                return "encrypt";
            }

            // 读取文件全部内容
            String content = Files.readString(path, StandardCharsets.UTF_8);

            // AES-256-GCM 加密
            String encrypted = CryptoUtil.encrypt(content, password);

            // 按行读取，将加密内容插入第二行（index=1）
            List<String> lines = new ArrayList<>(Arrays.asList(
                    content.split("\n", -1)));
            if (lines.size() < 2) {
                // 不足两行则补一行空行再插
                while (lines.size() < 1) lines.add("");
                lines.add(1, encrypted);
            } else {
                lines.add(1, encrypted);
            }

            Files.writeString(path, String.join("\n", lines), StandardCharsets.UTF_8);

            model.addAttribute("done", true);
        } catch (Exception e) {
            log.warn("encrypt error type={}", e.getClass().getSimpleName());
            model.addAttribute("error", "Error: " + e.getClass().getSimpleName());
        }
        return "encrypt";
    }

}
