package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "binance_square_token_stat",
        uniqueConstraints = @UniqueConstraint(name = "uk_post_token", columnNames = {"post_id", "token"}))
public class BinanceSquareTokenStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false, length = 100)
    private String postId;

    @Column(name = "token", nullable = false, length = 50)
    private String token;

    @Column(name = "in_content")
    private Boolean inContent;

    @Column(name = "in_fields")
    private Boolean inFields;

    @Column(name = "in_binance")
    private Boolean inBinance;

    @Column(name = "likes")
    private Integer likes;

    @Column(name = "comments")
    private Integer comments;

    @Column(name = "score")
    private Integer score;

    @Column(name = "post_date", nullable = false)
    private LocalDateTime postDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
