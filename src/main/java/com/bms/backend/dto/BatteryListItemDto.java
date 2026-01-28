package com.bms.backend.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 列表返回用DTO
 */
@Data
public class BatteryListItemDto {
    private Long id;
    // ID列
    private String batteryCode;
    // 型号
    private String modelCode;
    // 客户
    private String customerName;
    // 投运日期
    private LocalDate commissioningDate;
    // 当前状态
    private Short status;
    // 额定容量
    private BigDecimal ratedCapacityAh;
    // SOH
    private BigDecimal sohPercent;
    // 循环数
    private Integer cycleCount;
    // 最近记录时间
    private OffsetDateTime lastRecordAt;
}
