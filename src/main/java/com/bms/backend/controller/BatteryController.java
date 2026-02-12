package com.bms.backend.controller;


import com.bms.backend.dto.*;
import com.bms.backend.entity.BatteryRecord;
import com.bms.backend.service.BatteryCsvService;
import com.bms.backend.service.BatteryService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

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
        System.out.println("=== BatteryController.upload CSV called, file=" + file.getOriginalFilename());
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

    /**
     * 编辑
     * @param id
     * @param request
     * @return
     */
    @PutMapping("/{id}")
    public BatteryListItemDto update(@PathVariable Long id , @RequestBody BatteryCreateRequest request) {
        return batteryService.updateBattery(id , request);
    }


    /**
     * 删除
     * @param id
     * @return
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBattery(@PathVariable Long id) {
        try {
            batteryService.deleteBattery(id);
            return ResponseEntity.noContent().build();  // 204无内容
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();   // 404未找到
        }
    }

    /**
     * 按ID查询电池详情
     */
    @GetMapping("/{id}")
    public BatteryListItemDto getById(@PathVariable Long id) {
        return batteryService.getBatteryDetail(id);
    }


}
