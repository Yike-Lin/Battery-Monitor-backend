package com.bms.backend.dto;

import lombok.Data;

@Data
public class TopologySummaryDto {
    private Integer total = 0;
    private Integer normal = 0;
    private Integer warn = 0;
    private Integer alarm = 0;
}

