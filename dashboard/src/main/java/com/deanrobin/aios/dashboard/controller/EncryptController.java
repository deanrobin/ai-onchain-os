package com.deanrobin.aios.dashboard.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.*;

@Controller
@RequestMapping("/encrypt")
public class EncryptController {

    // 只记录"有调用"，不记录任何参数或内容
    private static final Logger log = LoggerFactory.getLogger(EncryptController.class);

    private static final int SALT_LEN  = 16;
    private static final int IV_LEN    = 12;
    private static final int ITER      = 65536;
    private static final int KEY_BITS  = 256;
    private static final int GCM_TAG   = 128;
    private static final String ALGO   = "AES/GCM/NoPadding";

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
            String encrypted = encrypt(content, password);

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

    // ── 加密工具 ──────────────────────────────────────────────
    private String encrypt(String plaintext, String password) throws Exception {
        SecureRandom rng = new SecureRandom();

        byte[] salt = new byte[SALT_LEN];
        byte[] iv   = new byte[IV_LEN];
        rng.nextBytes(salt);
        rng.nextBytes(iv);

        SecretKey key = deriveKey(password, salt);

        Cipher cipher = Cipher.getInstance(ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // 格式：Base64(salt + iv + ciphertext)
        byte[] combined = new byte[SALT_LEN + IV_LEN + ciphertext.length];
        System.arraycopy(salt,       0, combined, 0,                      SALT_LEN);
        System.arraycopy(iv,         0, combined, SALT_LEN,               IV_LEN);
        System.arraycopy(ciphertext, 0, combined, SALT_LEN + IV_LEN,      ciphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    private SecretKey deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITER, KEY_BITS);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }
}
