package com.bms.backend.service;

import com.bms.backend.dto.BatteryCreateRequest;
import com.bms.backend.dto.BatteryListItemDto;
import com.bms.backend.dto.BatteryListQuery;
import com.bms.backend.dto.LifecyclePointDto;
import com.bms.backend.dto.SohErrorDto;
import com.bms.backend.entity.Battery;
import com.bms.backend.entity.BatteryCsvUpload;
import com.bms.backend.entity.BatteryModel;
import com.bms.backend.entity.BatteryRecord;
import com.bms.backend.entity.Customer;
import com.bms.backend.exception.BusinessException;
import com.bms.backend.repository.BatteryCsvUploadRepository;
import com.bms.backend.repository.BatteryModelRepository;
import com.bms.backend.repository.BatteryRecordRepository;
import com.bms.backend.repository.BatteryRepository;
import com.bms.backend.repository.CustomerRepository;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.persistence.criteria.Predicate;

/**
 * 电池台账业务
 */
@Service
public class BatteryService {

    private final BatteryRepository batteryRepository;
    private final BatteryModelRepository batteryModelRepository;
    private final CustomerRepository customerRepository;
    private final BatteryRecordRepository batteryRecordRepository;
    private final BatteryCsvUploadRepository batteryCsvUploadRepository;
    private final BatteryCsvService batteryCsvService;

    public BatteryService(BatteryRepository batteryRepository,
                          BatteryModelRepository batteryModelRepository,
                          CustomerRepository customerRepository,
                          BatteryRecordRepository batteryRecordRepository,
                          BatteryCsvUploadRepository batteryCsvUploadRepository,
                          BatteryCsvService batteryCsvService){
        this.batteryRepository = batteryRepository;
        this.batteryModelRepository = batteryModelRepository;
        this.customerRepository = customerRepository;
        this.batteryRecordRepository = batteryRecordRepository;
        this.batteryCsvUploadRepository = batteryCsvUploadRepository;
        this.batteryCsvService = batteryCsvService;
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
        // 0. 先校验 batteryCode
        if (request.getBatteryCode() == null || request.getBatteryCode().trim().isEmpty()) {
            throw new BusinessException("电池编码不能为空！");
        }
        String batteryCode = request.getBatteryCode().trim();
        if (batteryRepository.existsByBatteryCode(batteryCode)) {
            throw new BusinessException("电池编码已存在，请更换后再保存！");
        }

        // 1. modelCode、customerName不允许为空
        if(request.getModelCode() == null || request.getModelCode().trim().isEmpty()) {
            throw new BusinessException("电池型号不能为空！");
        }
        String modelCode = request.getModelCode().trim();
        if (request.getCustomerName() == null || request.getCustomerName().trim().isEmpty()) {
            throw new BusinessException("所属客户不能为空！");
        }
        String customerName = request.getCustomerName().trim();

        // 2. 处理型号:先查一下，没有就新建
        BatteryModel model = batteryModelRepository.findByModelCode(modelCode);
        if (model == null) {
            model = BatteryModel.builder()
                    .modelCode(request.getModelCode())
                    .build();
            model = batteryModelRepository.save(model);
        }

        // 3. 处理客户:先查一下，没有就新建
        Customer customer = customerRepository.findByName(customerName);
        if (customer == null) {
            customer = Customer.builder()
                    .name(customerName)
                    .deleted(false)
                    .build();
            customer = customerRepository.save(customer);
        }

        // 4. 解析 lastRecordAt（ISO 字符串）
        OffsetDateTime lastRecordAt = null;
        if (request.getLastRecordAt() != null && !request.getLastRecordAt().isEmpty()) {
            lastRecordAt = OffsetDateTime.parse(request.getLastRecordAt());
        }

        // 5. 先看有没有uploadToken，有就先做SOH预测
        String uploadToken = request.getUploadToken();
        Double sohPredict = null;
        if (uploadToken != null && !uploadToken.trim().isEmpty()) {
            sohPredict = batteryCsvService.predictSohByUploadToken(uploadToken);
            System.out.println("=== createBattery sohPredict from CSV = " + sohPredict);
        }

        // 6. 处理BigDecimal类型字段
        BigDecimal sohValue = null;
        if (sohPredict != null) {
            sohValue = BigDecimal
                    .valueOf(sohPredict)
                    .multiply(BigDecimal.valueOf(100));
        } else {
            sohValue = request.getSohPercent();
        }

        // 7. 创建Battery实体
        Battery battery = Battery.builder()
                .batteryCode(batteryCode)
                .model(model)
                .customer(customer)
                .status(request.getStatus() != null ? request.getStatus() : 1)
                .commissioningDate(request.getCommissioningDate())
                .ratedCapacityAh(request.getRatedCapacityAh())
                .sohPercent(sohValue)
                .cycleCount(request.getCycleCount())
                .lastRecordAt(lastRecordAt)
                .deleted(false)
                .build();

        battery = batteryRepository.save(battery);

        // 8. 如果请求里带uploadToken，把这次上传记录绑定到电池
        if (uploadToken != null && !uploadToken.trim().isEmpty()) {
            batteryCsvService.bindUploadToBattery(battery.getId(), uploadToken);
        }

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


    /**
     * 更新数据
     * @param id
     * @param request
     * @return
     */
    @Transactional
    public BatteryListItemDto updateBattery(Long id, BatteryCreateRequest request) {
        // 1. 查出已有的 Battery，然后检验未删除
        Battery battery = batteryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("电池不存在 , id =" + id));
        if(Boolean.TRUE.equals(battery.getDeleted())) {
            throw new BusinessException("该电池已被删除，无法编辑！");
        }

        // 2. 校验batteryCode
        if (request.getBatteryCode() == null || request.getBatteryCode().trim().isEmpty()) {
            throw new BusinessException("电池编码不能为空！");
        }
        String batteryCode = request.getBatteryCode().trim();
        // 编辑时校验编码唯一
        boolean existsCode = batteryRepository.existsByBatteryCodeAndIdNot(batteryCode , id);
        if (existsCode) {
            throw new BusinessException("电池编码已经存在，请更换后再保存！");
        }

        // 3. 校验 modelCode
        if (request.getModelCode() == null || request.getModelCode().isEmpty()) {
            throw new BusinessException("电池型号不能为空！");
        }
        String modelCode = request.getModelCode().trim();

        // 4. 校验 customerName
        if (request.getCustomerName() == null || request.getCustomerName().isEmpty()) {
            throw new BusinessException("所属客户不能为空！");
        }
        String customerName = request.getCustomerName().trim();

        // 5. 处理model：先查，没有再新建
        BatteryModel model = batteryModelRepository.findByModelCode(modelCode);
        if (model == null) {
            model = new BatteryModel();
            model.setModelCode(modelCode);
            model = batteryModelRepository.save(model);
        }

        // 6. 处理customer：先查，没有再新建
        Customer customer = customerRepository.findByName(customerName);
        if (customer == null) {
            customer = new Customer();
            customer.setName(customerName);
            customer.setDeleted(false);
            customer = customerRepository.save(customer);
        }

        // 7. 解析 lastRecordAt
        OffsetDateTime lastRecordAt = null;
        if (request.getLastRecordAt() != null && !request.getLastRecordAt().isEmpty()) {
            lastRecordAt = OffsetDateTime.parse(request.getLastRecordAt());
        }

        // 8. 更新字段
        battery.setBatteryCode(batteryCode);
        battery.setModel(model);
        battery.setCustomer(customer);
        battery.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        battery.setCommissioningDate(request.getCommissioningDate());
        battery.setRatedCapacityAh(request.getRatedCapacityAh());
        battery.setSohPercent(request.getSohPercent());
        battery.setCycleCount(request.getCycleCount());
        battery.setLastRecordAt(lastRecordAt);

        // 9. 保存
        Battery saved = batteryRepository.save(battery);

        // 10. 返回列表DTO
        return toListItemDto(saved);
    }



    /**
     * 获取最新添加的一个电池（单体电池展示用）
     * @return
     */
    public BatteryListItemDto getLatestBattery() {
        return batteryRepository.findTopByOrderByIdDesc()
                .map(this::toListItemDto)
                .orElse(null);
    }


    /**
     * 按ID查询电池详情
     */
    public BatteryListItemDto getBatteryDetail(Long id) {
        Battery battery = batteryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("电池不存在, id = " + id));

        if (Boolean.TRUE.equals(battery.getDeleted())) {
            throw new BusinessException("该电池已被删除！");
        }

        return toListItemDto(battery);
    }

    /**
     * 计算已入库电池的 SOH 误差（基于数据库最新记录）：
     * pred_soh = battery.soh_percent / 100
     * true_soh = latest_capacity / rated_capacity
     */
    @Transactional(readOnly = true)
    public SohErrorDto getSohError(Long id) {
        Battery battery = batteryRepository.findById(id)
                .orElseThrow(() -> new BusinessException("电池不存在, id = " + id));
        if (Boolean.TRUE.equals(battery.getDeleted())) {
            throw new BusinessException("该电池已被删除，无法计算误差");
        }
        SohErrorDto dto = new SohErrorDto();
        dto.setBatteryId(battery.getId());
        dto.setBatteryCode(battery.getBatteryCode());
        dto.setRatedCapacityAh(battery.getRatedCapacityAh() != null ? battery.getRatedCapacityAh().doubleValue() : null);
        dto.setCalculable(false);
        dto.setReason("缺少必要数据");

        if (battery.getSohPercent() != null) {
            dto.setPredSoh(battery.getSohPercent().doubleValue() / 100.0);
        } else {
            dto.setReason("battery.sohPercent 为空，请先完成 SOH 预测并保存");
        }

        if (battery.getRatedCapacityAh() == null || battery.getRatedCapacityAh().doubleValue() <= 0) {
            dto.setReason("battery.ratedCapacityAh 无效或为空");
            return dto;
        }

        BatteryRecord latest = batteryRecordRepository
                .findTopByBatteryIdAndCapacityIsNotNullOrderByCycleDescTimeMinDesc(id)
                .orElse(null);

        Integer latestCycle = null;
        Double trueCapacity = null;
        if (latest != null) {
            latestCycle = latest.getCycle();
            trueCapacity = latest.getCapacity();
        } else {
            // 兜底：某些流程未落库 battery_record，但 CSV 生命周期可读
            List<LifecyclePointDto> lifecycle = batteryCsvService.getLifecycleCapacityTrend(id);
            if (lifecycle != null && !lifecycle.isEmpty()) {
                LifecyclePointDto last = lifecycle.get(lifecycle.size() - 1);
                latestCycle = last.getCycle();
                trueCapacity = last.getCapacityAh();
            }
        }

        if (trueCapacity == null) {
            dto.setReason("无可用容量记录（battery_record 和 CSV 生命周期均为空）");
            return dto;
        }

        double ratedCapacity = battery.getRatedCapacityAh().doubleValue();
        double trueSoh = trueCapacity / ratedCapacity;

        dto.setLatestCycle(latestCycle);
        dto.setTrueCapacityAh(trueCapacity);
        dto.setTrueSoh(trueSoh);

        if (dto.getPredSoh() != null) {
            double absError = Math.abs(dto.getPredSoh() - trueSoh);
            double apePercent = absError / (Math.abs(trueSoh) + 1e-8) * 100.0;
            dto.setAbsError(absError);
            dto.setApePercent(apePercent);
            dto.setCalculable(true);
            dto.setReason("ok");
        }
        return dto;
    }

    /**
     * 重新计算并回写 SOH（基于最新绑定 CSV）
     */
    @Transactional
    public BatteryListItemDto recalculateSoh(Long id) {
        Battery battery = batteryRepository.findById(id)
                .orElseThrow(() -> new BusinessException("电池不存在, id = " + id));
        if (Boolean.TRUE.equals(battery.getDeleted())) {
            throw new BusinessException("该电池已被删除，无法重算 SOH");
        }

        BatteryCsvUpload upload = batteryCsvUploadRepository
                .findTopByBatteryIdOrderByUsedAtDesc(id)
                .orElseThrow(() -> new BusinessException("该电池没有关联的上传记录，无法重算 SOH"));

        Double sohPredict = batteryCsvService.predictSohByUploadToken(upload.getUploadToken());
        if (sohPredict == null) {
            throw new BusinessException("SOH 重算失败：预测结果为空");
        }

        battery.setSohPercent(BigDecimal.valueOf(sohPredict).multiply(BigDecimal.valueOf(100)));
        Battery saved = batteryRepository.save(battery);
        return toListItemDto(saved);
    }
}
























