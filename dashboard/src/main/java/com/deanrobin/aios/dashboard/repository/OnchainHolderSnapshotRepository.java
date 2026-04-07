package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.OnchainHolderSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OnchainHolderSnapshotRepository extends JpaRepository<OnchainHolderSnapshot, Long> {

    Optional<OnchainHolderSnapshot> findTopByWatchIdAndWalletAddrOrderBySnappedAtDesc(Long watchId, String walletAddr);

    @Modifying
    @Transactional
    @Query("DELETE FROM OnchainHolderSnapshot s WHERE s.snappedAt < :cutoff")
    int deleteBySnappedAtBefore(LocalDateTime cutoff);
}
