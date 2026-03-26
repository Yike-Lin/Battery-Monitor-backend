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
import java.util.Optional;
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

        Object lock = LOCKS.computeIfAbsent(batteryCodeKey, k -> new Object());
        synchronized (lock) {
            // 幂等去重：同电池+相同 soh/source/model 的重复请求（例如双击）直接复用最近一条
            Optional<SohAnnotation> latestSameOpt = sohAnnotationRepository.findTopByBatteryCodeAndSohPercentAndSourceAndModelVersionOrderByCreatedAtDesc(
                    batteryCode,
                    normalizedSoh,
                    source,
                    modelVersion
            );

            long nowMs = OffsetDateTime.now().toInstant().toEpochMilli();
            if (latestSameOpt.isPresent()) {
                SohAnnotation existing = latestSameOpt.get();
                if (existing.getCreatedAt() != null) {
                    long ageMs = Math.abs(nowMs - existing.getCreatedAt().toInstant().toEpochMilli());
                    if (ageMs <= idempotentWindowMs) {
                        // 人情：如果用户补充了信息（note/annotatedBy/predicted），就把旧记录更新一下
                        if (req.getNote() != null && !req.getNote().trim().isEmpty()) {
                            existing.setNote(req.getNote());
                        }
                        if (req.getAnnotatedBy() != null && !req.getAnnotatedBy().trim().isEmpty()) {
                            existing.setAnnotatedBy(req.getAnnotatedBy());
                        }
                        if (req.getPredictedSohPercent() != null) {
                            existing.setPredictedSohPercent(req.getPredictedSohPercent());
                            existing.setPredictedAt(OffsetDateTime.now());
                        }
                        return sohAnnotationRepository.save(existing);
                    }
                }
            }

            Battery battery = batteryRepository.findByBatteryCode(batteryCode);
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
        Pageable pageable = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.ASC, "createdAt"));
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

