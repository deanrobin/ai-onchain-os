package com.deanrobin.aios.dashboard.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "my_address")
public class MyAddress {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String address;
    private String chainIndex;
    private String label;
    private Boolean isActive;
    private LocalDateTime createdAt;
}
