package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.repository.BinanceSquareTokenStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 币安广场热度聚合只读服务（Web + 飞书报告复用）。
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class BinanceSquareService {

    private final BinanceSquareTokenStatRepository tokenStatRepo;

    /** 最近 N 小时 TopN 热度榜。 */
    public List<Map<String, Object>> topTokensSince(int hours, int limit) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        List<Object[]> rows = tokenStatRepo.aggregateSince(since, limit);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("token",     (String) r[0]);
            m.put("score",     toInt(r[1]));
            m.put("likes",     toInt(r[2]));
            m.put("comments",  toInt(r[3]));
            m.put("postCount", toInt(r[4]));
            m.put("inBinance", toInt(r[5]) > 0);
            out.add(m);
        }
        return out;
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        if (o instanceof BigDecimal bd) return bd.intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; }
    }
}
