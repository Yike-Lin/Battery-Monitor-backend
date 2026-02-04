package com.bms.backend.controller;

import com.bms.backend.dto.BatteryRecordDto;
import com.bms.backend.dto.BatteryRecordQuery;
import com.bms.backend.service.BatteryRecordService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/batteries/{batteryId}/records")
public class BatteryRecordController {

    private final BatteryRecordService batteryRecordService;

    public BatteryRecordController(BatteryRecordService batteryRecordService) {
        this.batteryRecordService = batteryRecordService;
    }


    /**
     * 分页查询电池的测试记录
     * @param batteryId
     * @param query
     * @return queryBatteryRecords
     */
    @GetMapping
    public Page<BatteryRecordDto> listRecords(@PathVariable Long batteryId,
                                              BatteryRecordQuery query) {
        return batteryRecordService.queryBatteryRecords(batteryId , query);
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
        return batteryRecordService.getBatteryRecordsByCycle(batteryId , cycle);
    }


}
