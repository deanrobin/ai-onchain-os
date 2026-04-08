package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.PerpSupplySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PerpSupplySnapshotRepository extends JpaRepository<PerpSupplySnapshot, Long> {

    Optional<PerpSupplySnapshot> findByBaseCurrency(String baseCurrency);
}
