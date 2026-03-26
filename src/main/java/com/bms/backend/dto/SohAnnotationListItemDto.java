package com.bms.backend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * SohAnnotationListItemDto 用于展示 SOH 标注列表项
 */
@Data
public class SohAnnotationListItemDto {
    private Long id;
    private String batteryCode;
    private BigDecimal sohPercent;
    private String source;
    private String modelVersion;
    private BigDecimal predictedSohPercent;
    private String note;
    private OffsetDateTime createdAt;
}

