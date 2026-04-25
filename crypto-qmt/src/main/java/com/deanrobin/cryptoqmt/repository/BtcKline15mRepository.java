package com.deanrobin.cryptoqmt.repository;

import com.deanrobin.cryptoqmt.model.BtcKline15m;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BtcKline15mRepository extends JpaRepository<BtcKline15m, Long> {

    Optional<BtcKline15m> findByOpenTime(LocalDateTime openTime);

    @Query("SELECT k FROM BtcKline15m k WHERE k.openTime >= :since ORDER BY k.openTime ASC")
    List<BtcKline15m> findSince(@Param("since") LocalDateTime since);

    @Query("SELECT k FROM BtcKline15m k ORDER BY k.openTime DESC")
    List<BtcKline15m> findLatest(org.springframework.data.domain.Pageable pageable);

    @Query("SELECT MAX(k.openTime) FROM BtcKline15m k")
    LocalDateTime findMaxOpenTime();

    @Query("SELECT MIN(k.openTime) FROM BtcKline15m k")
    LocalDateTime findMinOpenTime();

    long countByOpenTimeGreaterThanEqual(LocalDateTime since);
}
