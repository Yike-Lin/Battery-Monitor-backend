package com.bms.backend.repository;

import com.bms.backend.entity.Battery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BatteryRepository
        extends JpaRepository<Battery, Long>, JpaSpecificationExecutor<Battery> {

    Battery findByBatteryCode(String batteryCode);

    boolean existsByBatteryCode(String batteryCode);
}