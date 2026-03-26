package com.bms.backend.repository;

import com.bms.backend.entity.SohAnnotation;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;

/**
 * SohAnnotationRepository 用于操作 SOH 标注信息
 */
public interface SohAnnotationRepository extends JpaRepository<SohAnnotation, Long> {

    /**
     * 用于幂等/去重：查最近一条相同 soh/source/model 的标注
     */
    java.util.Optional<SohAnnotation> findTopByBatteryCodeAndSohPercentAndSourceAndModelVersionOrderByCreatedAtDesc(
            @Param("batteryCode") String batteryCode,
            @Param("sohPercent") BigDecimal sohPercent,
            @Param("source") String source,
            @Param("modelVersion") String modelVersion
    );
}

