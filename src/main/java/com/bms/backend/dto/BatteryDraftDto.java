package com.bms.backend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 上传CSV后返回的“台账草稿”DTO
 */
@Data
public class BatteryDraftDto {
    // 用户填写
    private String batteryCode;
    private String modelCode;
    private String customerName;
    private BigDecimal ratedCapacityAh;

    // CSV自动解析出来
    private Integer cycleCount;
    private BigDecimal sohPercent;
    private OffsetDateTime lastRecordAt;

    // 临时解析文件的ID返回回去
    private String uploadToken;


}
