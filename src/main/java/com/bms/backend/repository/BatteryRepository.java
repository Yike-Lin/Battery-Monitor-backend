package com.bms.backend.repository;

import com.bms.backend.entity.Battery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.List;

public interface BatteryRepository
        extends JpaRepository<Battery, Long>, JpaSpecificationExecutor<Battery> {

    Battery findByBatteryCode(String batteryCode);
    // 用于兼容大小写不一致：避免前端/数据库编码差异导致有电池查不到
    Battery findByBatteryCodeIgnoreCase(String batteryCode);

    // 用于新增时的校验编码唯一
    boolean existsByBatteryCode(String batteryCode);

    // 用于编辑时的校验编码唯一
    boolean existsByBatteryCodeAndIdNot(String batteryCode, Long id);

    // 获取最新电池：按ID倒序排列取第一条
    Optional<Battery> findTopByOrderByIdDesc();

    // 大屏优先使用：按最近记录时间取最新两组电池
    List<Battery> findTop2ByDeletedFalseAndLastRecordAtIsNotNullOrderByLastRecordAtDescIdDesc();

    // 拓扑快照：查询全部在库电池
    List<Battery> findByDeletedFalse();
}