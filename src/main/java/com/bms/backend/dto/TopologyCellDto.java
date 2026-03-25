package com.bms.backend.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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

