package com.deanrobin.aios.dashboard.service;

import com.deanrobin.aios.dashboard.model.BinanceSquareRankSnapshot;
import com.deanrobin.aios.dashboard.repository.BinanceSquareRankSnapshotRepository;
import com.deanrobin.aios.dashboard.repository.BinanceSquareTokenStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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

    private final BinanceSquareTokenStatRepository    tokenStatRepo;
    private final BinanceSquareRankSnapshotRepository snapshotRepo;

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

    /**
     * 当前 TopN + 与最近一次快照的排名差（delta > 0 表示上升）。
     * 每条多出字段：
     *   prevRank     : Integer|null   上次排名（null 表示上次不在榜里）
     *   rankDelta    : Integer|null   prevRank - currentRank，null 表示新入榜
     *   hasSnapshot  : boolean        是否存在任意历史快照（用来区分「尚无快照」vs「新入榜」）
     */
    public List<Map<String, Object>> topTokensWithDelta(int hours, int limit) {
        List<Map<String, Object>> current = topTokensSince(hours, limit);
        if (current.isEmpty()) return current;

        // 找最近一次「不是 5 分钟内」的快照，避免和自己对比得到全 0
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        LocalDateTime prevAt = snapshotRepo.findLatestSnapshotTimeBefore(hours, cutoff)
                .orElseGet(() -> snapshotRepo.findLatestSnapshotTime(hours).orElse(null));

        Map<String, Integer> prevRankMap = new HashMap<>();
        boolean hasSnapshot = prevAt != null;
        if (hasSnapshot) {
            for (BinanceSquareRankSnapshot s :
                    snapshotRepo.findByWindowHoursAndSnapshotAtOrderByRankNo(hours, prevAt)) {
                prevRankMap.put(s.getToken(), s.getRankNo());
            }
        }

        for (int i = 0; i < current.size(); i++) {
            Map<String, Object> row = current.get(i);
            int currRank = i + 1;
            Integer prevRank = prevRankMap.get(String.valueOf(row.get("token")));
            row.put("prevRank",    prevRank);
            row.put("rankDelta",   prevRank == null ? null : (prevRank - currRank));
            row.put("hasSnapshot", hasSnapshot);
        }
        return current;
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        if (o instanceof BigDecimal bd) return bd.intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; }
    }
}
