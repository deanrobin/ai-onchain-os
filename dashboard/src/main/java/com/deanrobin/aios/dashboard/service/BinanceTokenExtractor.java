package com.deanrobin.aios.dashboard.service;

import lombok.Getter;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 币安广场代币抽取器（对应 Python v5）。
 *
 * 提取规则：
 *   - 正文中 $ 开头 + 首字符字母/CJK（不匹配 $19、$500 等价格）
 *   - 长度 1-15（保留单字母代币 $A / $W 等币安真实代币）
 *   - 同时读取作者主动标注字段 tradingPairs / tradingPairsV2 /
 *     userInputTradingPairs / coinPairList
 */
public final class BinanceTokenExtractor {

    // $ 后面必须是字母或 CJK（非数字），后续 0-14 个字母/数字/CJK
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "\\$([A-Za-z\\u4e00-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7af]"
                    + "[A-Za-z0-9\\u4e00-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7af]{0,14})");

    private static final Set<String> STOP_WORDS = Set.of(
            "USD", "USDT", "USDC", "EUR", "JPY", "CNY", "CZ"
    );

    private static final String[] FIELDS = {
            "tradingPairs", "tradingPairsV2",
            "userInputTradingPairs", "coinPairList"
    };

    private static final String[] STABLE_SUFFIXES = {"USDT", "BUSD", "FDUSD", "USDC"};

    private BinanceTokenExtractor() {}

    @Getter
    public static final class Extracted {
        private final Set<String> fromContent;
        private final Set<String> fromFields;
        private final Set<String> all;

        Extracted(Set<String> fromContent, Set<String> fromFields) {
            this.fromContent = fromContent;
            this.fromFields = fromFields;
            Set<String> u = new HashSet<>(fromContent);
            u.addAll(fromFields);
            this.all = u;
        }
    }

    public static Extracted extract(Map<String, Object> post) {
        Set<String> fromContent = extractFromContent(
                post.get("content") instanceof String s ? s : null);
        Set<String> fromFields = extractFromFields(post);
        return new Extracted(fromContent, fromFields);
    }

    static Set<String> extractFromContent(String content) {
        Set<String> result = new HashSet<>();
        if (content == null || content.isEmpty()) return result;
        Matcher m = TOKEN_PATTERN.matcher(content);
        while (m.find()) {
            String raw = m.group(1);
            boolean ascii = raw.chars().allMatch(c -> c < 128);
            String norm = ascii ? raw.toUpperCase() : raw;  // CJK 保持原样
            if (!STOP_WORDS.contains(norm)) result.add(norm);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Set<String> extractFromFields(Map<String, Object> post) {
        Set<String> result = new HashSet<>();
        if (post == null) return result;
        for (String field : FIELDS) {
            Object v = post.get(field);
            if (!(v instanceof List<?> pairs)) continue;
            for (Object item : pairs) {
                if (!(item instanceof Map<?, ?> mp)) continue;
                String code = strVal(mp, "code");
                if (code != null) { result.add(code); continue; }
                String base = strVal(mp, "baseAsset");
                if (base != null) { result.add(base); continue; }
                String symbol = strVal(mp, "symbol");
                if (symbol == null) continue;
                String val = symbol;
                for (String suf : STABLE_SUFFIXES) {
                    if (val.endsWith(suf) && val.length() > suf.length()) {
                        val = val.substring(0, val.length() - suf.length());
                        break;
                    }
                }
                result.add(val);
            }
        }
        result.removeIf(STOP_WORDS::contains);
        return result;
    }

    private static String strVal(Map<?, ?> mp, String key) {
        Object v = mp.get(key);
        if (v instanceof String s) {
            String t = s.trim();
            return t.isEmpty() ? null : t;
        }
        return null;
    }
}
