package com.bms.backend.dto;

import lombok.Data;

/**
 * SohErrorDto 用于存储SOH误差信息
 */
@Data
public class SohErrorDto {
    private Long batteryId;
    private String batteryCode;
    private Integer latestCycle;
    private Double ratedCapacityAh;
    private Double trueCapacityAh;
    private Double predSoh;
    private Double trueSoh;
    private Double absError;
    private Double apePercent;
    private Boolean calculable;
    private String reason;
}
