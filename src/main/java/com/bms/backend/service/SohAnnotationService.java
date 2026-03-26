package com.bms.backend.service;

import com.bms.backend.dto.SohAnnotationCreateRequest;
import com.bms.backend.dto.SohAnnotationListItemDto;
import com.bms.backend.entity.Battery;
import com.bms.backend.entity.SohAnnotation;
import com.bms.backend.exception.BusinessException;
import com.bms.backend.repository.BatteryRepository;
import com.bms.backend.repository.SohAnnotationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SohAnnotationService 用于处理 SOH 标注业务逻辑
 */
@Service
public class SohAnnotationService {

    private final BatteryRepository batteryRepository;
    private final SohAnnotationRepository sohAnnotationRepository;

    @Value("${bms.soh.model-version:unknown}")
    private String defaultModelVersion;

    @Value("${bms.soh.model-path:}")
    private String defaultModelPath;

    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    @Value("${bms.soh.annotation-idempotent-window-ms:600000}")
    private long idempotentWindowMs;

    private static final Set<String> ALLOWED_SOURCES = new HashSet<>(Arrays.asList(
            "manual", "lab", "predicted", "imported"
    ));

    public SohAnnotationService(BatteryRepository batteryRepository,
                                 SohAnnotationRepository sohAnnotationRepository) {
        this.batteryRepository = batteryRepository;
        this.sohAnnotationRepository = sohAnnotationRepository;
    }

    /**
     * 创建 SOH 标注
     * @param req 标注请求
     * @return 创建的标注
     */
    @Transactional
    public SohAnnotation create(SohAnnotationCreateRequest req) {
        if (req == null) throw new BusinessException("标注请求为空");
        if (req.getBatteryCode() == null || req.getBatteryCode().trim().isEmpty()) {
            throw new BusinessException("batteryCode 不能为空");
        }
        if (req.getSohPercent() == null) throw new BusinessException("sohPercent 不能为空");
        if (req.getSource() == null || req.getSource().trim().isEmpty()) {
            throw new BusinessException("source 不能为空");
        }

        // 1. 参数校验
        String batteryCode = req.getBatteryCode().trim();
        String batteryCodeKey = batteryCode.toLowerCase();
        String source = req.getSource().trim();
        if (!ALLOWED_SOURCES.contains(source)) {
            throw new BusinessException("source 不合法：允许值为 " + ALLOWED_SOURCES);
        }

        // sohPercent 实体 scale=2，幂等查询时先归一化，否则 BigDecimal 比较会因为精度不同导致查不到
        BigDecimal normalizedSoh = req.getSohPercent().setScale(2, RoundingMode.HALF_UP);
        double sohVal = normalizedSoh.doubleValue();
        if (sohVal < 0.0 || sohVal > 100.0) {
            throw new BusinessException("sohPercent 超出范围 [0,100]");
        }

        String modelVersion = req.getModelVersion() != null && !req.getModelVersion().trim().isEmpty()
                ? req.getModelVersion().trim()
                : defaultModelVersion;

        // 2. 并发控制：使用 ConcurrentHashMap 作为轻量级锁
        Object lock = LOCKS.computeIfAbsent(batteryCodeKey, k -> new Object());
        synchronized (lock) {
            Battery battery = batteryRepository.findByBatteryCode(batteryCode);
            if (battery == null) {
                // 兼容大小写不一致：避免前端/数据库编码差异导致“明明有电池但查不到”
                battery = batteryRepository.findByBatteryCodeIgnoreCase(batteryCode);
            }
            if (battery == null || Boolean.TRUE.equals(battery.getDeleted())) {
                throw new BusinessException("电池不存在或已删除：batteryCode=" + batteryCode);
            }

            SohAnnotation ann = new SohAnnotation();
            ann.setBatteryId(battery.getId());
            ann.setBatteryCode(batteryCode);
            ann.setSohPercent(normalizedSoh);
            ann.setSource(source);

            ann.setModelVersion(modelVersion);
            ann.setModelPath(req.getModelPath() != null && !req.getModelPath().trim().isEmpty()
                    ? req.getModelPath().trim()
                    : (defaultModelPath == null || defaultModelPath.trim().isEmpty() ? null : defaultModelPath.trim()));

            ann.setPredictedSohPercent(req.getPredictedSohPercent());
            if (req.getPredictedSohPercent() != null) {
                ann.setPredictedAt(OffsetDateTime.now());
            }

            ann.setAnnotatedBy(req.getAnnotatedBy());
            ann.setNote(req.getNote());

            return sohAnnotationRepository.save(ann);
        }
    }

    /**
     * 拉取 SOH 标注记录（按 createdAt 升序，即添加顺序）
     */
    public List<SohAnnotationListItemDto> listAll(int limit) {
        int safeLimit = limit <= 0 ? 200 : Math.min(limit, 2000);
        Sort sort = Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"));
        Pageable pageable = PageRequest.of(0, safeLimit, sort);
        Page<SohAnnotation> page = sohAnnotationRepository.findAll(pageable);
        return page.getContent().stream().map(this::toListItemDto).collect(Collectors.toList());
    }

    private SohAnnotationListItemDto toListItemDto(SohAnnotation ann) {
        SohAnnotationListItemDto dto = new SohAnnotationListItemDto();
        dto.setId(ann.getId());
        dto.setBatteryCode(ann.getBatteryCode());
        dto.setSohPercent(ann.getSohPercent());
        dto.setSource(ann.getSource());
        dto.setModelVersion(ann.getModelVersion());
        dto.setPredictedSohPercent(ann.getPredictedSohPercent());
        dto.setNote(ann.getNote());
        dto.setCreatedAt(ann.getCreatedAt());
        return dto;
    }
}

