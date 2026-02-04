package com.bms.backend.service;


import com.bms.backend.dto.BatteryRecordDto;
import com.bms.backend.dto.BatteryRecordQuery;
import com.bms.backend.entity.Battery;
import com.bms.backend.entity.BatteryRecord;
import com.bms.backend.exception.BusinessException;
import com.bms.backend.repository.BatteryRecordRepository;
import com.bms.backend.repository.BatteryRepository;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class BatteryRecordService {

    private final BatteryRepository batteryRepository;
    private final BatteryRecordRepository batteryRecordRepository;

    public BatteryRecordService(BatteryRepository batteryRepository,
                                BatteryRecordRepository batteryRecordRepository) {
        this.batteryRepository = batteryRepository;
        this.batteryRecordRepository = batteryRecordRepository;
    }

    /**
     * 分页查询某块电池的测试记录
     * @param batteryId
     * @param query
     * @return
     */
    @Transactional(readOnly = true)
    public Page<BatteryRecordDto> queryBatteryRecords(Long batteryId , BatteryRecordQuery query) {
        // 1. 确认电池是否存在
        Battery battery = batteryRepository.findById(batteryId)
                .orElseThrow(() -> new BusinessException("电池不存在，id = " + batteryId));
        if (Boolean.TRUE.equals(battery.getDeleted())) {
            throw new BusinessException("该电池已被删除，无法查看记录！");
        }

        // 2. 处理分页参数
        int page = query.getPage() != null && query.getPage() >= 0 ? query.getPage() : 0;
        int size = query.getSize() != null && query.getSize() > 0 ? query.getSize() : 50;
        if (size > 500) {
            size = 500;
        }
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.asc("cycle"), Sort.Order.asc("timeMin")));
        Page<BatteryRecord> pageResult;

        // 3. 按是否指定cycle起止来决定使用哪个Repository方法
        Integer cycleStart = query.getCycleStart();
        Integer cycleEnd = query.getCycleEnd();

        // 情况1：有头有尾（Between）
        if (cycleStart != null && cycleEnd != null) {
            // 如果前端传反了（start > end），交换一下
            if (cycleStart > cycleEnd) {
                int tmp = cycleStart;
                cycleStart = cycleEnd;
                cycleEnd = tmp;
            }
            pageResult = batteryRecordRepository
                    .findByBatteryIdAndCycleBetween(batteryId, cycleStart , cycleEnd , pageable);
        // 情况2：只有头
        } else if (cycleStart != null) {
            pageResult = batteryRecordRepository
                    .findByBatteryIdAndCycleGreaterThanEqual(batteryId , cycleStart , pageable);
        // 情况3： 只有尾
        } else if (cycleEnd != null) {
            pageResult = batteryRecordRepository
                    .findByBatteryIdAndCycleLessThanEqual(batteryId , cycleEnd , pageable);
        // 情况4： 啥没有
        } else {
            pageResult = batteryRecordRepository.findBatteryId(batteryId , pageable);
        }

        // 4. 转成DTO
        List<BatteryRecordDto> dtoList = pageResult.getContent().stream()
                .map(this::toRecordDto)
                .collect(Collectors.toList());

        return new PageImpl<>(dtoList , pageable , pageResult.getTotalElements());
    }

    /**
     * 手工转换逻辑toRecordDto
     * @param record
     * @return
     */
    private BatteryRecordDto toRecordDto(BatteryRecord record) {
        BatteryRecordDto dto = new BatteryRecordDto();
        dto.setId(record.getId());
        // 防一手，如果record.getBattery()是null，直接调.getId()，直接崩
        dto.setBatteryId(record.getBattery() != null ? record.getBattery().getId() : null);
        dto.setCycle(record.getCycle());
        dto.setTimeMin(record.getTimeMin());
        dto.setVoltage(record.getVoltage());
        dto.setCurrent(record.getCurrent());
        dto.setTemp(record.getTemp());
        dto.setCapacity(record.getCapacity());
        dto.setSourceFile(record.getSourceFile());
        dto.setUploadBatch(record.getUploadBatch());
        return dto;
    }


    /**
     * 按单个循环查询所有记录（画图用）
     * @param batteryId
     * @param cycle
     * @return
     */
    @Transactional(readOnly = true)
    public List<BatteryRecordDto> getBatteryRecordsByCycle(Long batteryId , Integer cycle) {

        // 1. 安检
        if (cycle == null) {
            throw new BusinessException("查询循环记录时，cycle不能为空！");
        }
        Battery battery = batteryRepository.findById(batteryId)
                .orElseThrow(() -> new BusinessException("电池不存在，id = " + batteryId));
        if (Boolean.TRUE.equals(battery.getDeleted())) {
            throw new BusinessException("该电池已经被删除，无法查看记录！");
        }

        // 2. 取货
        List<BatteryRecord> list = batteryRecordRepository
                .findByBatteryIdAndCycleOrderByTimeMinAsc(batteryId , cycle);

        return list.stream().map(this::toRecordDto).collect(Collectors.toList());
    }









}
