package com.bms.backend.service;

import com.bms.backend.dto.BatteryCreateRequest;
import com.bms.backend.dto.BatteryListItemDto;
import com.bms.backend.dto.BatteryListQuery;
import com.bms.backend.entity.Battery;
import com.bms.backend.entity.BatteryModel;
import com.bms.backend.entity.Customer;
import com.bms.backend.repository.BatteryModelRepository;
import com.bms.backend.repository.BatteryRepository;
import com.bms.backend.repository.CustomerRepository;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.persistence.criteria.Predicate;

/**
 * 电池台账业务
 */
@Service
public class BatteryService {

    private final BatteryRepository batteryRepository;
    private final BatteryModelRepository batteryModelRepository;
    private final CustomerRepository customerRepository;

    public BatteryService(BatteryRepository batteryRepository,
                          BatteryModelRepository batteryModelRepository,
                          CustomerRepository customerRepository){
        this.batteryRepository = batteryRepository;
        this.batteryModelRepository = batteryModelRepository;
        this.customerRepository = customerRepository;
    }

    /**
     * 列表分页查询
     * @param query
     * @return PageImpl
     */
    public Page<BatteryListItemDto> queryBatteryList(BatteryListQuery query) {
        // 1.动态查询条件Specification
        Specification<Battery> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // 按电池编号模糊查询
            if (query.getBatteryCode() != null && !query.getBatteryCode().isEmpty()) {
                predicates.add(cb.like(root.get("batteryCode") , "%" + query.getBatteryCode() + "%"));
            }
            // 按电池型号模糊查询
            if (query.getModelCode() != null && !query.getModelCode().isEmpty()) {
                predicates.add(cb.like(root.join("model").get("modelCode") , "%" + query.getModelCode() + "%"));
            }
            // 按状态等值查询
            if (query.getStatus() != null) {
                predicates.add(cb.equal(root.get("status") , query.getStatus()));
            }
            // 按投运日期 >= 起始日期
            if (query.getCommissioningDateStart() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("commissioningDate") , query.getCommissioningDateStart()));
            }
            // 按投运日期 <= 截止日期
            if (query.getCommissioningDateEnd() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("commissioningDate") , query.getCommissioningDateEnd()));
            }
            // 只查未删除的数据
            predicates.add(cb.isFalse(root.get("deleted")));

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        // 2.构造分页 + 排序
        Pageable pageable = PageRequest.of(
                query.getPage(),
                query.getSize(),
                // 默认按最近记录时间倒序
                Sort.by(Sort.Direction.DESC , "lastRecordAt")
        );

        // 3.调repository查数据库
        Page<Battery> page = batteryRepository.findAll(spec, pageable);

        // 4.转成DTO
        List<BatteryListItemDto> dtoList = page.getContent().stream()
                .map(this::toListItemDto)
                .collect(Collectors.toList());

        return new PageImpl<>(dtoList , pageable , page.getTotalElements());
    }

    /**
     * 数据库查出的Battery实体——>前端列表用的BatteryListItemDto
     * @param battery
     * @return dto
     */
    private BatteryListItemDto toListItemDto(Battery battery){
        BatteryListItemDto dto = new BatteryListItemDto();
        dto.setId(battery.getId());
        dto.setBatteryCode(battery.getBatteryCode());
        dto.setStatus(battery.getStatus());
        dto.setCommissioningDate(battery.getCommissioningDate());
        dto.setRatedCapacityAh(battery.getRatedCapacityAh());
        dto.setSohPercent(battery.getSohPercent());
        dto.setCycleCount(battery.getCycleCount());
        dto.setLastRecordAt(battery.getLastRecordAt());

        // 处理关联对象：如果电池有型号，就从型号对象中取出modelCode，Copy到DTO的modelCode
        if (battery.getModel() != null) {
            dto.setModelCode(battery.getModel().getModelCode());
        }
        if (battery.getCustomer() != null) {
            dto.setCustomerName(battery.getCustomer().getName());
        }
        return dto;
    }


    /**
     * 新增入库
     * @param request
     * @return
     */
    @Transactional
    public BatteryListItemDto createBattery(BatteryCreateRequest request){
        // 1. 处理型号:先查一下，没有就新建
        BatteryModel model = batteryModelRepository.findByModelCode(request.getModelCode());
        if (model == null) {
            model = BatteryModel.builder()
                    .modelCode(request.getModelCode())
                    .build();
            model = batteryModelRepository.save(model);
        }

        // 2. 处理客户
        Customer customer = null;
        if (request.getCustomerName() != null && !request.getCustomerName().isEmpty()) {
            customer = customerRepository.findByName(request.getCustomerName());
            if (customer == null){
                customer = Customer.builder()
                        .name(request.getCustomerName())
                        .build();
                customer = customerRepository.save(customer);
            }
        }

        OffsetDateTime lastRecordAt = null;
        if (request.getLastRecordAt() != null && !request.getLastRecordAt().isEmpty()) {
            // 直接用 OffsetDateTime 自带的解析（支持 2026-01-31T22:35:14.857+08:00）
            lastRecordAt = OffsetDateTime.parse(request.getLastRecordAt());
        }

        // 3. 创建Battery实体
        Battery battery = Battery.builder()
                .batteryCode(request.getBatteryCode())
                .model(model)
                .customer(customer)
                .status(request.getStatus() != null ? request.getStatus() : 1)
                .commissioningDate(request.getCommissioningDate())
                .ratedCapacityAh(request.getRatedCapacityAh())
                .sohPercent(request.getSohPercent())
                .cycleCount(request.getCycleCount())
                .lastRecordAt(lastRecordAt)
                .deleted(false)
                .build();

        battery = batteryRepository.save(battery);
        return toListItemDto(battery);
    }

    /**
     * 逻辑删除：标记 deleted = true
     * @param id
     */
    @Transactional
    public void deleteBattery(Long id) {
        Battery battery = batteryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("电池不存在：id = " + id));
        // 已经删除，直接返回
        if (Boolean.TRUE.equals(battery.getDeleted())) {
            return;
        }
        battery.setDeleted(true);
        batteryRepository.save(battery);

    }



}
