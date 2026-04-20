package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "binance_square_rank_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_snap_token",
                columnNames = {"snapshot_at", "window_hours", "token"}))
public class BinanceSquareRankSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_at", nullable = false)
    private LocalDateTime snapshotAt;

    @Column(name = "window_hours", nullable = false)
    private Integer windowHours;

    @Column(name = "rank_no", nullable = false)
    private Integer rankNo;

    @Column(name = "token", nullable = false, length = 50)
    private String token;

    @Column(name = "score", nullable = false)
    private Integer score;
}
