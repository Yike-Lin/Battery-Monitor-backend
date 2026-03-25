package com.bms.backend.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * PACK 拓扑矩阵监控数据单体电池DTO
 * 包括电池PACK ID、电池ID、行、列、模块索引、电压、温度、电流、得分、状态、原因。
 */
@Data
public class TopologyCellDto {
    private String packId;
    private String cellId;
    private Integer row;
    private Integer col;
    private Integer moduleIndex;
    private Double voltage;
    private Double temperature;
    private Double current;
    private Integer score;
    private String status; // normal | warn | alarm
    private List<String> reasons = new ArrayList<>();
}

