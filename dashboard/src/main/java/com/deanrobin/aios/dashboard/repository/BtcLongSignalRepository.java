package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.BtcLongSignal;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BtcLongSignalRepository extends JpaRepository<BtcLongSignal, Long> {

    @Query("SELECT s FROM BtcLongSignal s ORDER BY s.signalTime DESC")
    List<BtcLongSignal> findLatest(Pageable pageable);

    @Query("SELECT s FROM BtcLongSignal s WHERE s.status = :status ORDER BY s.signalTime DESC")
    List<BtcLongSignal> findByStatus(@Param("status") String status, Pageable pageable);

    @Query("SELECT s FROM BtcLongSignal s WHERE s.alertStatus = 'PENDING' ORDER BY s.signalTime ASC")
    List<BtcLongSignal> findPendingAlerts();

    long countByStatus(String status);

    long countBySignalTimeGreaterThanEqual(LocalDateTime since);
}
