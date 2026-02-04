package com.bms.backend.repository;

import com.bms.backend.entity.BatteryRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BatteryRecordRepository extends JpaRepository<BatteryRecord , Long> {

    // 按电池ID 查询所有记录（分页）
    Page<BatteryRecord> findByBatteryId(Long batteryId , Pageable pageable);

    // 按电池ID + cycle范围 查询(分页）
    Page<BatteryRecord> findByBatteryIdAndCycleBetween(Long batteryId,
                                                       Integer cycleStart,
                                                       Integer cycleEnd,
                                                       Pageable pageable);

    // 按电池ID + cycle >= 下限 查询（分页）
    Page<BatteryRecord> findByBatteryIdAndCycleGreaterThanEqual(Long batteryId,
                                                                Integer cycleStart,
                                                                Pageable pageable);
    // 按电池ID + cycle >= 上限 查询（分页）
    Page<BatteryRecord> findByBatteryIdAndCycleLessThanEqual(Long batteryId,
                                                             Integer cycleEnd,
                                                             Pageable pageable);

    // 按电池ID + 单个cycle查询（按timeMin升序，画曲线用）
    List<BatteryRecord> findByBatteryIdAndCycleOrderByTimeMinAsc(Long batteryId , Integer cycle);

}
