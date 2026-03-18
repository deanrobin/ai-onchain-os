package com.deanrobin.aios.dashboard.controller;

import com.deanrobin.aios.dashboard.service.TradeService;
import com.deanrobin.aios.dashboard.vo.TradeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * 交易页面控制器
 * GET  /trade        → 渲染 trade.html
 * POST /trade/execute → JSON 执行交易，返回 { success, txHash, errorMsg }
 */
@Log4j2
@Controller
@RequiredArgsConstructor
public class TradeController {

    private final TradeService tradeService;

    @GetMapping("/trade")
    public String tradePage(Model model) {
        model.addAttribute("activePage", "trade");
        return "trade";
    }

    /**
     * 执行交易
     * @param chain    "bsc" 或 "sol"
     * @param tokenCA  目标代币合约地址 (CA)
     * @param amount   金额（BNB 或 SOL 数量，如 0.1）
     * @param slippage 滑点，可选，支持小数（0.1 = 10%）或百分比（10 = 10%），默认 0.1（10%）
     */
    @PostMapping("/trade/execute")
    @ResponseBody
    public ResponseEntity<TradeResult> executeT(
            @RequestParam String chain,
            @RequestParam String tokenCA,
            @RequestParam String amount,
            @RequestParam(required = false) String slippage) {

        // ── 基础校验 ──────────────────────────────────────────────────
        if (chain == null || chain.isBlank()
                || tokenCA == null || tokenCA.isBlank()
                || amount == null || amount.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(TradeResult.error("参数不完整（chain / tokenCA / amount 均为必填）"));
        }
        chain   = chain.trim().toLowerCase();
        tokenCA = tokenCA.trim();
        amount  = amount.trim();

        if (!chain.equals("bsc") && !chain.equals("sol")
                && !chain.equals("56") && !chain.equals("501")) {
            return ResponseEntity.badRequest()
                    .body(TradeResult.error("chain 仅支持 bsc / sol"));
        }
        try {
            new java.math.BigDecimal(amount);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest()
                    .body(TradeResult.error("amount 格式错误（需为数字，如 0.1）"));
        }
        if (slippage != null && !slippage.isBlank()) {
            try {
                new java.math.BigDecimal(slippage.trim());
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest()
                        .body(TradeResult.error("slippage 格式错误（如 0.1 表示10%，或直接填 10）"));
            }
        }

        log.info("Trade request chain={} tokenCA={} amount={} slippage={}", chain, tokenCA, amount,
                slippage != null && !slippage.isBlank() ? slippage : "default(10%)");
        TradeResult result = tradeService.executeTrade(chain, tokenCA, amount, slippage);
        return ResponseEntity.ok(result);
    }
}
