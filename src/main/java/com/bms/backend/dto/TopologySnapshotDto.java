package com.bms.backend.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * PACK 拓扑矩阵监控数据DTO
 * 包括电池PACK ID、时间戳、是否过期、摘要、电池列表。
 */
@Data
public class TopologySnapshotDto {
    private String packId;
    private Long ts;
    private boolean stale;
    private TopologySummaryDto summary = new TopologySummaryDto();
    private List<TopologyCellDto> cells = new ArrayList<>();
}

