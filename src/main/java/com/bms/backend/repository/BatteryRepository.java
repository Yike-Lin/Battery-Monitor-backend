package com.bms.backend.repository;

import com.bms.backend.entity.Battery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BatteryRepository
        extends JpaRepository<Battery, Long>, JpaSpecificationExecutor<Battery> {

    Battery findByBatteryCode(String batteryCode);

    // 用于新增时的校验编码唯一
    boolean existsByBatteryCode(String batteryCode);

    // 用于编辑时的校验编码唯一
    boolean existsByBatteryCodeAndIdNot(String batteryCode, Long id);
}