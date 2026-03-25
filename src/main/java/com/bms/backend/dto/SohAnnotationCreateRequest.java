package com.bms.backend.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SohAnnotationCreateRequest {
    // 电池台账编号（battery_code）
    private String batteryCode;

    // 标注 SOH (%)
    private BigDecimal sohPercent;

    // 来源：manual / lab / predicted / imported
    private String source;

    // 模型版本/算法版本（用于追溯）
    private String modelVersion;

    // 可选：模型路径/标识
    private String modelPath;

    // 可选：标注时的预测 SOH
    private BigDecimal predictedSohPercent;

    // 可选：标注人
    private String annotatedBy;

    // 可选：备注
    private String note;
}

