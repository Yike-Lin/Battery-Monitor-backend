package com.bms.backend.repository;

import com.bms.backend.entity.BatteryModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BatteryModelRepository extends JpaRepository<BatteryModel , Long> {
    BatteryModel findByModelCode(String modelCode);
}