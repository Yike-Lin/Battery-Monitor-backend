package com.bms.backend.dto;


import lombok.Data;


/**
 * 单个曲线点DTO，前端画图用
 */
@Data
public class BatteryCurvePointDto {
    private Integer Cycle;
    private Double timeMin;
    private Double voltage;
    private Double current;
    private Double temp;
    private Double capacity;
}
