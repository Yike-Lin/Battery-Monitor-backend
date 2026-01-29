package com.bms.backend.controller;


import com.bms.backend.dto.BatteryCreateRequest;
import com.bms.backend.dto.BatteryDraftDto;
import com.bms.backend.dto.BatteryListItemDto;
import com.bms.backend.dto.BatteryListQuery;
import com.bms.backend.service.BatteryCsvService;
import com.bms.backend.service.BatteryService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 电池接口Controller
 */
@RestController
@RequestMapping("/api/batteries")
public class BatteryController {
    private final BatteryService batteryService;
    private final BatteryCsvService batteryCsvService;

    public BatteryController(BatteryService batteryService,
                             BatteryCsvService batteryCsvService) {
        this.batteryService = batteryService;
        this.batteryCsvService = batteryCsvService;
    }

    /**
     * 列表查询
     * @param query
     * @return
     */
    @GetMapping
    public Page<BatteryListItemDto> list(BatteryListQuery query) {
        return batteryService.queryBatteryList(query);
    }

    /**
     * 上传CSV：返回台账草稿
     * @param file
     * @return
     * @throws IOException
     */
    @PostMapping("/upload")
    public BatteryDraftDto upload(@RequestParam("file")MultipartFile file) throws IOException {
        return batteryCsvService.parseCsvToDraft(file);
    }

    /**
     * 新增入库：保存一条电池台账记录
     * @param request
     * @return
     */
    @PostMapping
    public BatteryListItemDto create(@RequestBody BatteryCreateRequest request) {
        return batteryService.createBattery(request);
    }


}
