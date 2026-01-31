package com.bms.backend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 提交新增入库的请求DTO
 */
@Data
public class BatteryCreateRequest {

    private String batteryCode;
    private String modelCode;
    private String customerName;
    private LocalDate commissioningDate;
    private Short status;
    private BigDecimal ratedCapacityAh;
    private BigDecimal sohPercent;
    private Integer cycleCount;
    private String lastRecordAt;

    // 对应上传CSV时产生的一个token
    private String uploadToken;
}
