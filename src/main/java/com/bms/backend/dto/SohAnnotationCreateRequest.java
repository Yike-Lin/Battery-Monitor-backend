package com.bms.backend.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * SohAnnotationCreateRequest 用于创建 SOH 标注请求
 * @author 
 * @version 1.0
 * @since 2026-03-25
 */
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

    // 模型路径/标识
    private String modelPath;

    // 标注时的预测 SOH
    private BigDecimal predictedSohPercent;

    // 标注人
    private String annotatedBy;

    // 备注
    private String note;
}

