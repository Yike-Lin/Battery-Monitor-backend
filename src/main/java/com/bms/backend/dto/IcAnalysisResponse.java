package com.bms.backend.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class IcAnalysisResponse {
    private String cellId;
    private Integer refCycle;
    private Integer currCycle;
    private Double peakShift;
    private Double peakDropPercent;
    private List<IcPointDto> refCurve = new ArrayList<>();
    private List<IcPointDto> currCurve = new ArrayList<>();
}

