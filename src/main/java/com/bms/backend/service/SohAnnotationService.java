package com.bms.backend.service;

import com.bms.backend.dto.SohAnnotationCreateRequest;
import com.bms.backend.entity.Battery;
import com.bms.backend.entity.SohAnnotation;
import com.bms.backend.exception.BusinessException;
import com.bms.backend.repository.BatteryRepository;
import com.bms.backend.repository.SohAnnotationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class SohAnnotationService {

    private final BatteryRepository batteryRepository;
    private final SohAnnotationRepository sohAnnotationRepository;

    @Value("${bms.soh.model-version:unknown}")
    private String defaultModelVersion;

    @Value("${bms.soh.model-path:}")
    private String defaultModelPath;

    public SohAnnotationService(BatteryRepository batteryRepository,
                                 SohAnnotationRepository sohAnnotationRepository) {
        this.batteryRepository = batteryRepository;
        this.sohAnnotationRepository = sohAnnotationRepository;
    }

    @Transactional
    public SohAnnotation create(SohAnnotationCreateRequest req) {
        if (req == null) {
            throw new BusinessException("标注请求为空");
        }
        if (req.getBatteryCode() == null || req.getBatteryCode().trim().isEmpty()) {
            throw new BusinessException("batteryCode 不能为空");
        }
        if (req.getSohPercent() == null) {
            throw new BusinessException("sohPercent 不能为空");
        }
        if (req.getSource() == null || req.getSource().trim().isEmpty()) {
            throw new BusinessException("source 不能为空");
        }

        String batteryCode = req.getBatteryCode().trim();
        Battery battery = batteryRepository.findByBatteryCode(batteryCode);
        if (battery == null || Boolean.TRUE.equals(battery.getDeleted())) {
            throw new BusinessException("电池不存在或已删除：batteryCode=" + batteryCode);
        }

        SohAnnotation ann = new SohAnnotation();
        ann.setBatteryId(battery.getId());
        ann.setBatteryCode(batteryCode);
        ann.setSohPercent(req.getSohPercent());
        ann.setSource(req.getSource().trim());

        ann.setModelVersion(req.getModelVersion() != null && !req.getModelVersion().trim().isEmpty()
                ? req.getModelVersion().trim()
                : defaultModelVersion);
        ann.setModelPath(req.getModelPath() != null && !req.getModelPath().trim().isEmpty()
                ? req.getModelPath().trim()
                : (defaultModelPath == null || defaultModelPath.trim().isEmpty() ? null : defaultModelPath.trim()));

        ann.setPredictedSohPercent(req.getPredictedSohPercent());
        if (req.getPredictedSohPercent() != null) {
            // 前端未传 predictedAt 时，用当前时间作为可追溯时间点
            ann.setPredictedAt(OffsetDateTime.now());
        }

        ann.setAnnotatedBy(req.getAnnotatedBy());
        ann.setNote(req.getNote());

        return sohAnnotationRepository.save(ann);
    }
}

