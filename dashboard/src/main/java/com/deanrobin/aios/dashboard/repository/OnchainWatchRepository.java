package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.OnchainWatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OnchainWatchRepository extends JpaRepository<OnchainWatch, Long> {

    List<OnchainWatch> findAllByIsActiveTrue();
}
