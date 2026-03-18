package com.deanrobin.aios.dashboard.controller;

import com.deanrobin.aios.dashboard.service.TransferService;
import com.deanrobin.aios.dashboard.vo.TradeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * 转账页面控制器
 * GET  /transfer         → 渲染 transfer.html
 * POST /transfer/execute → JSON 执行转账，返回 { success, txHash, errorMsg }
 */
@Log4j2
@Controller
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @GetMapping("/transfer")
    public String transferPage(Model model) {
        model.addAttribute("activePage", "transfer");
        return "transfer";
    }

    /**
     * 执行转账
     * @param chain     "bsc" 或 "sol"
     * @param toAddress 收款地址
     * @param tokenType "native"（BNB/SOL）或 "usdt"
     * @param amount    金额（如 "0.5" 或 "100"）
     */
    @PostMapping("/transfer/execute")
    @ResponseBody
    public ResponseEntity<TradeResult> executeTransfer(
            @RequestParam String chain,
            @RequestParam String toAddress,
            @RequestParam String tokenType,
            @RequestParam String amount) {

        // ── 基础校验 ──────────────────────────────────────────────
        if (chain == null || chain.isBlank()
                || toAddress == null || toAddress.isBlank()
                || tokenType == null || tokenType.isBlank()
                || amount == null || amount.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(TradeResult.error("参数不完整（chain / toAddress / tokenType / amount 均为必填）"));
        }
        chain     = chain.trim().toLowerCase();
        toAddress = toAddress.trim();
        tokenType = tokenType.trim().toLowerCase();
        amount    = amount.trim();

        if (!chain.equals("bsc") && !chain.equals("sol")) {
            return ResponseEntity.badRequest()
                    .body(TradeResult.error("chain 仅支持 bsc / sol"));
        }
        if (!tokenType.equals("native") && !tokenType.equals("usdt")) {
            return ResponseEntity.badRequest()
                    .body(TradeResult.error("tokenType 仅支持 native / usdt"));
        }
        try {
            new java.math.BigDecimal(amount);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest()
                    .body(TradeResult.error("amount 格式错误（需为数字，如 0.5）"));
        }
        if (new java.math.BigDecimal(amount).compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest()
                    .body(TradeResult.error("amount 必须大于 0"));
        }

        log.info("Transfer request chain={} to={} token={} amount={}", chain, toAddress, tokenType, amount);
        TradeResult result = transferService.executeTransfer(chain, toAddress, tokenType, amount);
        return ResponseEntity.ok(result);
    }
}
