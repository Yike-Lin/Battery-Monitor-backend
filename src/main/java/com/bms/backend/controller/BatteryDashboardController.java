package com.bms.backend.controller;

import com.bms.backend.dto.IcAnalysisResponse;
import com.bms.backend.dto.TopologySnapshotDto;
import com.bms.backend.entity.DashboardData;
import com.bms.backend.service.BatteryDataService;
import com.bms.backend.service.TopologySnapshotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/battery-dashboard")
@CrossOrigin(origins = "*")
public class BatteryDashboardController {

    @Autowired
    private BatteryDataService batteryDataService;
    @Autowired
    private TopologySnapshotService topologySnapshotService;

    // 前端调用地址: GET /api/battery/stream
    @GetMapping("/stream")
    public DashboardData getStream(
            // 不传 idA/idB 时：自动从 InfluxDB 选择“最新采样”的电池作为双通道数据源
            @RequestParam(required = false) String idA,
            @RequestParam(required = false) String idB
    ) {
        if (idA == null || idA.isEmpty() || idB == null || idB.isEmpty()) {
            return batteryDataService.getDashboardStreamLatestTwo();
        }
        return batteryDataService.getDashboardStream(idA, idB);
    }

    @GetMapping("/ic")
    public IcAnalysisResponse getIc(
            @RequestParam String cellId,
            @RequestParam(required = false) Integer refCycle,
            @RequestParam(required = false) Integer currCycle,
            @RequestParam(required = false, defaultValue = "5") Integer smooth
    ) {
        return batteryDataService.getIcAnalysis(cellId, refCycle, currCycle, smooth == null ? 5 : smooth);
    }

    /**
     * 获取PACK 拓扑矩阵监控数据
     * @param packId
     * @return
     */
    @GetMapping("/topology/snapshot")
    public TopologySnapshotDto getTopologySnapshot(
            @RequestParam(required = false) String packId
    ) {
        return topologySnapshotService.getSnapshot(packId);
    }

    @GetMapping("/topology/stream")
    public SseEmitter getTopologyStream(
            @RequestParam(required = false) String packId
    ) {
        return topologySnapshotService.subscribe(packId);
    }
}