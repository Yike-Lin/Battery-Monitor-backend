package com.bms.backend.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TopologySnapshotDto {
    private String packId;
    private Long ts;
    private boolean stale;
    private TopologySummaryDto summary = new TopologySummaryDto();
    private List<TopologyCellDto> cells = new ArrayList<>();
}

