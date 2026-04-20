package com.deanrobin.aios.dashboard.job;

import com.deanrobin.aios.dashboard.model.PerpInstrument;
import com.deanrobin.aios.dashboard.model.PerpOiWatchSnapshot;
import com.deanrobin.aios.dashboard.repository.PerpInstrumentRepository;
import com.deanrobin.aios.dashboard.repository.PerpOiAlertRepository;
import com.deanrobin.aios.dashboard.repository.PerpOiWatchSnapshotRepository;
import com.deanrobin.aios.dashboard.repository.PriceTickerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 特别关注品种 5 分钟快照 Job。
 *
 * 每 5 分钟检查 perp_oi_alert 表，对所有 watch_until > now 的品种
 * 各写一条快照记录（price_usd / change_24h / oi_usd），供事后复盘。
 *
 * 数据来源全部来自 DB（price_ticker + perp_instrument.latest_oi_usd），
 * 不直接调用交易所 API。
 *
 * ⚠️ 不加 @Transactional，避免长事务锁 DB
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class PerpWatchJob {

    private final PerpOiAlertRepository       alertRepo;
    private final PerpOiWatchSnapshotRepository watchSnapRepo;
    private final PerpInstrumentRepository    instrumentRepo;
    private final PriceTickerRepository       priceRepo;

    /**
     * 每 5 分钟采集一次特别关注品种的快照。
     * initialDelay 150s：在 PerpOiJob 初始化（120s）之后启动，
     * 确保初次运行时 perp_instrument.latest_oi_usd 已有数据。
     */
    @Scheduled(initialDelay = 150_000, fixedDelay = 300_000)
    public void snapshotWatchedSymbols() {
        LocalDateTime now = LocalDateTime.now();

        // 查询当前所有处于特别关注期的 (exchange, symbol)
        List<Object[]> activeWatches = alertRepo.findActiveWatchSymbols(now);
        if (activeWatches.isEmpty()) return;

        int saved = 0;
        for (Object[] row : activeWatches) {
            String exchange = (String) row[0];
            String symbol   = (String) row[1];
            try {
                if (snapshotOne(exchange, symbol, now)) saved++;
            } catch (Exception e) {
                log.warn("⚠️ 特别关注快照失败 {}:{} {}", exchange, symbol, e.getMessage());
            }
        }
        if (saved > 0) log.debug("📸 特别关注快照完成 | {} 条", saved);
    }

    // ─── 对单个品种做快照 ────────────────────────────────────────────────
    private boolean snapshotOne(String exchange, String symbol, LocalDateTime now) {
        // 从 perp_instrument 获取最新 OI（由 PerpOiJob 每 15min 更新）
        PerpInstrument inst = instrumentRepo.findByExchangeAndSymbol(exchange, symbol)
                .orElse(null);
        if (inst == null) return false;

        BigDecimal oiUsd = inst.getLatestOiUsd();

        // 从 price_ticker 获取价格和 24h 涨跌幅（baseCurrency 映射）
        BigDecimal priceUsd  = null;
        BigDecimal change24h = null;
        String base = inst.getBaseCurrency();
        if (base != null && !base.isBlank()) {
            var ticker = priceRepo.findBySymbol(base.toUpperCase());
            if (ticker.isPresent()) {
                priceUsd  = ticker.get().getPriceUsd();
                change24h = ticker.get().getChange24h() != null
                        ? ticker.get().getChange24h()
                        : null;
            }
        }

        PerpOiWatchSnapshot snap = new PerpOiWatchSnapshot();
        snap.setExchange(exchange);
        snap.setSymbol(symbol);
        snap.setPriceUsd(priceUsd);
        snap.setChange24h(change24h);
        snap.setOiUsd(oiUsd);
        snap.setSnappedAt(now);
        watchSnapRepo.save(snap);
        return true;
    }
}
