package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.PriceTicker;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PriceTickerRepository extends JpaRepository<PriceTicker, Long> {
    Optional<PriceTicker> findBySymbol(String symbol);
    List<PriceTicker> findAllByOrderBySymbolAsc();
}
