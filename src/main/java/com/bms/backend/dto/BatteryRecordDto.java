package com.bms.backend.dto;

import lombok.Data;

/**
 * 电池测试记录DTO（对应CSV每一行）
 */
@Data
public class BatteryRecordDto {

    private Long id;
    private Long batteryId;

    private Integer cycle;
    private Double timeMin;
    private Double voltage;
    private Double current;
    private Double temp;
    private Double capacity;

    // 来源信息
    private String sourceFile;
    private String uploadBatch;
}
