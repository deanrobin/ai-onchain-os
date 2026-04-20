package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "binance_square_post")
public class BinanceSquarePost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false, unique = true, length = 100)
    private String postId;

    @Column(name = "author_name", length = 200)
    private String authorName;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "like_count")
    private Integer likeCount;

    @Column(name = "comment_count")
    private Integer commentCount;

    @Column(name = "score")
    private Integer score;

    @Column(name = "tokens", length = 2000)
    private String tokens;

    @Column(name = "post_date", nullable = false)
    private LocalDateTime postDate;

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;
}
