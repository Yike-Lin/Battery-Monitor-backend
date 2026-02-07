package com.bms.backend.controller;

import com.bms.backend.dto.BatteryRecordDto;
import com.bms.backend.dto.BatteryRecordQuery;
import com.bms.backend.service.BatteryCsvService;
import com.bms.backend.service.BatteryRecordService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/batteries/{batteryId}/records")
public class BatteryRecordController {

    private final BatteryRecordService batteryRecordService;
    private final BatteryCsvService batteryCsvService;

    public BatteryRecordController(BatteryRecordService batteryRecordService, BatteryCsvService batteryCsvService) {
        this.batteryRecordService = batteryRecordService;
        this.batteryCsvService = batteryCsvService;
    }


    /**
     * 单个查询所有记录（画曲线）
     * @param batteryId
     * @param cycle
     * @return
     */
    @GetMapping("/by-cycle")
    public List<BatteryRecordDto> listRecordsByCycle(@PathVariable Long batteryId ,
                                                     @RequestParam("cycle") Integer cycle) {
        return batteryCsvService.getRecordsByBatteryAndCycle(batteryId , cycle);
    }


}
