package com.deanrobin.aios.dashboard.controller;

import com.deanrobin.aios.dashboard.model.BtcKline15m;
import com.deanrobin.aios.dashboard.model.BtcLongSignal;
import com.deanrobin.aios.dashboard.repository.BtcKline15mRepository;
import com.deanrobin.aios.dashboard.repository.BtcLongSignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
public class BtcStrategyController {

    private final BtcKline15mRepository     klineRepo;
    private final BtcLongSignalRepository   signalRepo;

    // ── 页面：BTC 做多策略看板 ──────────────────────────────────────
    @GetMapping("/btc-strategy")
    public String page(Model model) {
        model.addAttribute("activePage", "btc-strategy");

        long klineTotal = klineRepo.count();
        LocalDateTime maxOpen = klineRepo.findMaxOpenTime();
        LocalDateTime since7d = LocalDateTime.now().minusDays(7);

        model.addAttribute("klineTotal",     klineTotal);
        model.addAttribute("klineLatestAt",  maxOpen);
        model.addAttribute("klineRecent7d",  klineRepo.countByOpenTimeGreaterThanEqual(since7d));

        model.addAttribute("signalTotal",    signalRepo.count());
        model.addAttribute("signalOpen",     signalRepo.countByStatus("OPEN"));
        model.addAttribute("signalRecent7d", signalRepo.countBySignalTimeGreaterThanEqual(since7d));

        model.addAttribute("signals",   signalRepo.findLatest(PageRequest.of(0, 50)));
        model.addAttribute("klines",    klineRepo.findLatest(PageRequest.of(0, 20)));
        return "btc-strategy";
    }

    // ── API：最近 N 条信号（前端 30s 轮询） ───────────────────────────
    @GetMapping("/api/btc-strategy/signals")
    @ResponseBody
    public List<Map<String, Object>> apiSignals(
            @RequestParam(defaultValue = "50") int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        List<BtcLongSignal> list = signalRepo.findLatest(PageRequest.of(0, n));
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (BtcLongSignal s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",              s.getId());
            m.put("signalTime",      s.getSignalTime());
            m.put("strategyName",    s.getStrategyName());
            m.put("strategyVersion", s.getStrategyVersion());
            m.put("entryPrice",      s.getEntryPrice());
            m.put("takeProfitPrice", s.getTakeProfitPrice());
            m.put("stopLossPrice",   s.getStopLossPrice());
            m.put("takeProfitPct",   s.getTakeProfitPct());
            m.put("stopLossPct",     s.getStopLossPct());
            m.put("riskReward",      s.getRiskReward());
            m.put("confidence",      s.getConfidence());
            m.put("reason",          s.getReason());
            m.put("status",          s.getStatus());
            m.put("alertStatus",     s.getAlertStatus());
            m.put("realizedPct",     s.getRealizedPct());
            out.add(m);
        }
        return out;
    }

    // ── API：最近 N 条 K 线（用于页面小表） ──────────────────────────
    @GetMapping("/api/btc-strategy/klines")
    @ResponseBody
    public List<Map<String, Object>> apiKlines(
            @RequestParam(defaultValue = "20") int limit) {
        int n = Math.max(1, Math.min(limit, 500));
        List<BtcKline15m> list = klineRepo.findLatest(PageRequest.of(0, n));
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (BtcKline15m k : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("openTime",    k.getOpenTime());
            m.put("openPrice",   k.getOpenPrice());
            m.put("highPrice",   k.getHighPrice());
            m.put("lowPrice",    k.getLowPrice());
            m.put("closePrice",  k.getClosePrice());
            m.put("volume",      k.getVolume());
            m.put("ma20",        k.getMa20());
            m.put("ma120",       k.getMa120());
            m.put("macdDif",     k.getMacdDif());
            m.put("macdDea",     k.getMacdDea());
            m.put("macdHist",    k.getMacdHist());
            m.put("rsi21",       k.getRsi21());
            out.add(m);
        }
        return out;
    }
}
