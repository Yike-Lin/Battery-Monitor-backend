package com.bms.backend.repository;

import com.bms.backend.entity.BatteryRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BatteryRecordRepository extends JpaRepository<BatteryRecord , Long> {

    // 按电池查询所有记录
    Page<BatteryRecord> findBatteryId(Long batteryId , Pageable pageable);

    // 按电池 + cycle范围查询
    Page<BatteryRecord> findByBatteryIdAndCycleBetween(Long batteryId,
                                                       Integer cycleStart,
                                                       Integer cycleEnd,
                                                       Pageable pageable);

    // 按电池 + 单个cycle查询（画循环曲线用）
    List<BatteryRecord> findByBatteryIdAndCycleOrderByTimeMinAsc(Long batteryId , Integer cycle);

}
