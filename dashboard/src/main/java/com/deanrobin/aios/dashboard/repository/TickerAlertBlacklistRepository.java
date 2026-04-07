package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.TickerAlertBlacklist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Set;

public interface TickerAlertBlacklistRepository extends JpaRepository<TickerAlertBlacklist, Long> {

    @Query("SELECT b.symbol FROM TickerAlertBlacklist b")
    Set<String> findAllSymbols();
}
