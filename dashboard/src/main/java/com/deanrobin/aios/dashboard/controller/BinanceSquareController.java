package com.deanrobin.aios.dashboard.controller;

import com.deanrobin.aios.dashboard.service.BinanceSquareService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class BinanceSquareController {

    private final BinanceSquareService squareService;

    @GetMapping("/binance-square")
    public String page(
            @RequestParam(defaultValue = "1") int hours,
            Model model) {
        int window = (hours == 24 || hours == 8) ? hours : 1;
        model.addAttribute("activePage", "binance-square");
        model.addAttribute("hours", window);
        model.addAttribute("top", squareService.topTokensWithDelta(window, 20));
        return "binance-square";
    }

    @GetMapping("/api/binance-square/top")
    @ResponseBody
    public List<Map<String, Object>> top(
            @RequestParam(defaultValue = "1") int hours,
            @RequestParam(defaultValue = "20") int limit) {
        int window = hours <= 0 ? 1 : Math.min(hours, 168);
        int n      = limit <= 0 ? 20 : Math.min(limit, 100);
        return squareService.topTokensWithDelta(window, n);
    }
}
