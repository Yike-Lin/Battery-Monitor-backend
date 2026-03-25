package com.bms.backend.dto;

import lombok.Data;

/**
 * PACK 拓扑矩阵监控数据摘要DTO
 * 包括电池总数、正常电池数、警告电池数、报警电池数。
 */
@Data
public class TopologySummaryDto {
    private Integer total = 0;
    private Integer normal = 0;
    private Integer warn = 0;
    private Integer alarm = 0;
}

