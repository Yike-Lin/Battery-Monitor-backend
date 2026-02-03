package com.bms.backend.entity;

import lombok.*;
import javax.persistence.*;

@Entity
@Table(name = "battery_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatteryRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 关联到哪一块电池
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "battery_id",nullable = false)
    private Battery battery;

    // 循环编号
    @Column(name = "cycle",nullable = false)
    private Integer cycle;

    // 分钟
    @Column(name = "time_min",nullable = false)
    private Double timeMin;

    // 电压
    @Column(name = "voltage",nullable = false)
    private Double current;

    // 温度
    @Column(name = "temp")
    private Double temp;

    // 容量
    @Column(name = "capacity")
    private Double capacity;

    // 来源文件名
    @Column(name = "source_file")
    private String sourceFile;

    // 上传批次
    @Column(name = "upload_batch")
    private String uploadBatch;

    // @Column(name = "created_at")
    // private OffsetDateTime createdAt;


}
