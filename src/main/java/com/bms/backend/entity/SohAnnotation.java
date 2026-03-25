package com.bms.backend.entity;

import lombok.Data;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "soh_annotation")
@Data
public class SohAnnotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "battery_id")
    private Long batteryId;

    @Column(name = "battery_code", length = 64, nullable = false)
    private String batteryCode;

    @Column(name = "soh_percent", precision = 5, scale = 2, nullable = false)
    private BigDecimal sohPercent;

    /**
     * 标注来源：
     * - manual：人工标注
     * - lab：实验室结果
     * - predicted：来自模型预测（被人工确认/吸收）
     * - imported：导入/迁移
     */
    @Column(name = "source", length = 32, nullable = false)
    private String source;

    // 模型版本/算法版本（用于训练/校准可追溯）
    @Column(name = "model_version", length = 128)
    private String modelVersion;

    // 可选：记录当时 Python 模型文件路径/标识（如果有）
    @Column(name = "model_path", length = 512)
    private String modelPath;

    // 可选：保存标注前/当时的预测 SOH（便于误差分析）
    @Column(name = "predicted_soh_percent", precision = 5, scale = 2)
    private BigDecimal predictedSohPercent;

    @Column(name = "predicted_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime predictedAt;

    // 可选：标注人/系统
    @Column(name = "annotated_by", length = 64)
    private String annotatedBy;

    // 可选：备注
    @Column(name = "note", length = 1024)
    private String note;

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "TIMESTAMPTZ", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}

