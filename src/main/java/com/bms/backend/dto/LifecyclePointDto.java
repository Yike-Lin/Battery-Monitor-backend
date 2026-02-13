package com.bms.backend.dto;

import lombok.Data;

/**
 * 全生命周期曲线用DTO
 */
@Data
public class LifecyclePointDto {
    private Integer cycle;
    private Double capacityAh;
}
