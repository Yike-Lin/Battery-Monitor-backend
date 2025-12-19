package com.bms.backend.controller;

import com.bms.backend.entity.DashboardData;
import com.bms.backend.service.BatteryDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/battery")
@CrossOrigin(origins = "*")
public class BatteryController {

    @Autowired
    private BatteryDataService batteryDataService;

    // 前端调用地址: GET /api/battery/stream
    @GetMapping("/stream")
    public DashboardData getStream(
            // ⚠️ 这里定义默认值：Pack A = b1c0, Pack B = b1c1
            @RequestParam(defaultValue = "b1c0") String idA,
            @RequestParam(defaultValue = "b1c1") String idB
    ) {
        return batteryDataService.getDashboardStream(idA, idB);
    }
}