package com.deanrobin.aios.dashboard.repository;

import com.deanrobin.aios.dashboard.model.MyAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MyAddressRepository extends JpaRepository<MyAddress, Long> {
    List<MyAddress> findByIsActiveTrue();
}
