package com.deanrobin.aios.dashboard.controller;

import com.deanrobin.aios.dashboard.model.OnchainWatch;
import com.deanrobin.aios.dashboard.repository.OnchainWatchRepository;
import com.deanrobin.aios.dashboard.service.OnchainHolderService;
import com.deanrobin.aios.dashboard.service.OnchainRpcClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;

import java.math.BigDecimal;
import java.util.*;

/**
 * 链上持仓监控 Controller。
 *
 * <pre>
 * GET  /onchain-holder               页面
 * GET  /api/onchain-holder/list      监控任务列表
 * POST /api/onchain-holder/add       新增监控任务
 * DELETE /api/onchain-holder/{id}    删除监控任务
 * PATCH /api/onchain-holder/{id}/toggle 启用/禁用
 * GET  /api/onchain-holder/status    链状态（区块高度）
 * </pre>
 */
@Controller
@RequiredArgsConstructor
public class OnchainHolderController {

    private final OnchainWatchRepository  watchRepo;
    private final OnchainRpcClient        rpcClient;
    private final OnchainHolderService    holderService;

    // ── 页面 ──────────────────────────────────────────────────────────

    @GetMapping("/onchain-holder")
    public String page(Model model) {
        model.addAttribute("activePage", "onchain-holder");
        return "onchain-holder";
    }

    // ── REST ──────────────────────────────────────────────────────────

    @GetMapping("/api/onchain-holder/list")
    @ResponseBody
    public List<Map<String, Object>> list() {
        return watchRepo.findAll().stream().map(this::toMap).toList();
    }

    @PostMapping("/api/onchain-holder/add")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, Object> req) {
        try {
            OnchainWatch w = new OnchainWatch();
            w.setTokenName(str(req, "tokenName"));
            w.setContractAddr(str(req, "contractAddr").toLowerCase());
            w.setNetwork(str(req, "network").toUpperCase());
            w.setThresholdMode(str(req, "thresholdMode", "USD").toUpperCase());

            Object tusd = req.get("thresholdUsd");
            w.setThresholdUsd(tusd != null ? new BigDecimal(String.valueOf(tusd)) : new BigDecimal("50000"));

            Object tamt = req.get("thresholdAmount");
            if (tamt != null && !String.valueOf(tamt).isBlank()) {
                w.setThresholdAmount(new BigDecimal(String.valueOf(tamt)));
            }

            @SuppressWarnings("unchecked")
            List<String> addrs = (List<String>) req.get("watchedAddrs");
            if (addrs == null || addrs.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "至少填写一个关注地址"));
            }
            w.setWatchedAddrs(addrs.stream().map(String::toLowerCase).toList());

            // 尝试从链上查询精度
            try {
                Integer dec = rpcClient.getErc20Decimals(w.getNetwork(), w.getContractAddr());
                w.setTokenDecimals(dec != null ? dec : 18);
            } catch (Exception e) {
                w.setTokenDecimals(18);
            }

            watchRepo.save(w);
            return ResponseEntity.ok(Map.of("id", w.getId(), "msg", "保存成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/api/onchain-holder/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        if (!watchRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        watchRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("msg", "已删除"));
    }

    @PatchMapping("/api/onchain-holder/{id}/toggle")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable Long id) {
        return watchRepo.findById(id).map(w -> {
            w.setActive(!w.isActive());
            watchRepo.save(w);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("id", id);
            body.put("isActive", w.isActive());
            return ResponseEntity.ok(body);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/onchain-holder/status")
    @ResponseBody
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        Long ethBlock = rpcClient.getBlockNumber("ETH");
        Long bscBlock = rpcClient.getBlockNumber("BSC");
        result.put("eth", Map.of("blockNumber", ethBlock != null ? ethBlock : 0, "ok", ethBlock != null));
        result.put("bsc", Map.of("blockNumber", bscBlock != null ? bscBlock : 0, "ok", bscBlock != null));
        result.put("serverTime", java.time.LocalDateTime.now().toString());
        return result;
    }

    // ── 工具方法 ──────────────────────────────────────────────────────

    private Map<String, Object> toMap(OnchainWatch w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              w.getId());
        m.put("tokenName",       w.getTokenName());
        m.put("contractAddr",    w.getContractAddr());
        m.put("network",         w.getNetwork());
        m.put("tokenDecimals",   w.getTokenDecimals());
        m.put("thresholdMode",   w.getThresholdMode());
        m.put("thresholdAmount", w.getThresholdAmount());
        m.put("thresholdUsd",    w.getThresholdUsd());
        m.put("watchedAddrs",    w.getWatchedAddrs());
        m.put("isActive",        w.isActive());
        m.put("createdAt",       w.getCreatedAt() != null ? w.getCreatedAt().toString() : null);
        return m;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || String.valueOf(v).isBlank()) throw new IllegalArgumentException(key + " 不能为空");
        return String.valueOf(v).trim();
    }

    private static String str(Map<String, Object> m, String key, String defaultVal) {
        Object v = m.get(key);
        return (v == null || String.valueOf(v).isBlank()) ? defaultVal : String.valueOf(v).trim();
    }
}
