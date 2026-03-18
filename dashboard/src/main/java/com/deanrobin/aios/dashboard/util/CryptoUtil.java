package com.deanrobin.aios.dashboard.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * AES-256-GCM + PBKDF2 加解密工具。
 * 格式：Base64( salt[16] + iv[12] + ciphertext )
 */
public final class CryptoUtil {

    private static final int SALT_LEN = 16;
    private static final int IV_LEN   = 12;
    private static final int ITER     = 65536;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG  = 128;
    private static final String ALGO  = "AES/GCM/NoPadding";

    private CryptoUtil() {}

    public static String encrypt(String plaintext, String password) throws Exception {
        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[SALT_LEN];
        byte[] iv   = new byte[IV_LEN];
        rng.nextBytes(salt);
        rng.nextBytes(iv);

        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance(ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] out = new byte[SALT_LEN + IV_LEN + ct.length];
        System.arraycopy(salt, 0, out, 0,               SALT_LEN);
        System.arraycopy(iv,   0, out, SALT_LEN,        IV_LEN);
        System.arraycopy(ct,   0, out, SALT_LEN+IV_LEN, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }

    public static String decrypt(String base64, String password) throws Exception {
        byte[] raw  = Base64.getDecoder().decode(base64.trim());
        if (raw.length < SALT_LEN + IV_LEN + 1) throw new IllegalArgumentException("data too short");

        byte[] salt = new byte[SALT_LEN];
        byte[] iv   = new byte[IV_LEN];
        byte[] ct   = new byte[raw.length - SALT_LEN - IV_LEN];
        System.arraycopy(raw, 0,               salt, 0, SALT_LEN);
        System.arraycopy(raw, SALT_LEN,        iv,   0, IV_LEN);
        System.arraycopy(raw, SALT_LEN+IV_LEN, ct,   0, ct.length);

        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance(ALGO);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    /** 脱敏：固定8个*号 + 最后4位，如 ********ab1c */
    public static String mask(String plaintext) {
        if (plaintext == null) return "(null)";
        String s = plaintext.trim();
        int len = s.length();
        if (len <= 4) return "*".repeat(len);
        return "********" + s.substring(len - 4);
    }

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITER, KEY_BITS);
        return new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
    }
}
