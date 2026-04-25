package com.deanrobin.cryptoqmt.controller;

import com.deanrobin.cryptoqmt.model.BtcKline15m;
import com.deanrobin.cryptoqmt.repository.BtcKline15mRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class BtcStrategyController {

    private final BtcKline15mRepository klineRepo;

    @GetMapping("/")
    public String home() {
        return "redirect:/btc-strategy";
    }

    // ── 页面:BTC K 线看板 ─────────────────────────────────────────
    @GetMapping("/btc-strategy")
    public String page(Model model) {
        model.addAttribute("activePage", "btc-strategy");

        long klineTotal = klineRepo.count();
        LocalDateTime maxOpen = klineRepo.findMaxOpenTime();
        LocalDateTime minOpen = klineRepo.findMinOpenTime();
        LocalDateTime since7d = LocalDateTime.now().minusDays(7);

        model.addAttribute("klineTotal",     klineTotal);
        model.addAttribute("klineLatestAt",  maxOpen);
        model.addAttribute("klineEarliestAt", minOpen);
        model.addAttribute("klineRecent7d",  klineRepo.countByOpenTimeGreaterThanEqual(since7d));

        model.addAttribute("klines", klineRepo.findLatest(PageRequest.of(0, 20)));
        return "btc-strategy";
    }

    // ── API:最近 N 条 K 线(前端 30s 轮询) ───────────────────────
    @GetMapping("/api/btc-strategy/klines")
    @ResponseBody
    public List<Map<String, Object>> apiKlines(
            @RequestParam(defaultValue = "20") int limit) {
        int n = Math.max(1, Math.min(limit, 500));
        List<BtcKline15m> list = klineRepo.findLatest(PageRequest.of(0, n));
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (BtcKline15m k : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("openTime",   k.getOpenTime());
            m.put("openPrice",  k.getOpenPrice());
            m.put("highPrice",  k.getHighPrice());
            m.put("lowPrice",   k.getLowPrice());
            m.put("closePrice", k.getClosePrice());
            m.put("volume",     k.getVolume());
            m.put("ma20",       k.getMa20());
            m.put("ma120",      k.getMa120());
            m.put("macdDif",    k.getMacdDif());
            m.put("macdDea",    k.getMacdDea());
            m.put("macdHist",   k.getMacdHist());
            m.put("rsi21",      k.getRsi21());
            out.add(m);
        }
        return out;
    }
}
