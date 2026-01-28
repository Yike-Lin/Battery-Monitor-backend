package com.bms.backend.entity;


import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "battery")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Battery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 台账电池编号
    @Column(name = "battery_code" , nullable = false , unique = true , length = 64)
    private String batteryCode;

    // 电池型号ID
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_id" , nullable = false)
    private BatteryModel model;

    // 客户
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    // 当前状态: 1在役 2维护 3退役
    @Column(nullable = false)
    private Short status;

    // 投运日期
    @Column(name = "commissioning_date")
    private LocalDate commissioningDate;

    // 退役日期
    @Column(name = "decommission_date")
    private LocalDate decommissionDate;

    // 额定容量 (Ah)
    @Column(name = "rated_capacity_ah", nullable = false, precision = 10, scale = 3)
    private BigDecimal ratedCapacityAh;

    // SOH (%)
    @Column(name = "soh_percent", precision = 5, scale = 2)
    private BigDecimal sohPercent;

    // 循环数
    @Column(name = "cycle_count")
    private Integer cycleCount;

    // 最近记录时间
    @Column(name = "last_record_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime lastRecordAt;

    // 备注
    @Column
    private String remark;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        // 默认在役
        if (this.status == null){
            this.status = 1;
        }
    }
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

}
