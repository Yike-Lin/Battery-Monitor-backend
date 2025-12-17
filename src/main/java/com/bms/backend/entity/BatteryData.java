package com.bms.backend.entity;

import com.influxdb.annotations.Column;
import com.influxdb.annotations.Measurement;
import lombok.Data;
import java.time.Instant;

/* 设计InfluxDB的数据结构 */
@Data
@Measurement(name = "battery_status")
public class BatteryData {

    // 电池ID
    @Column(tag = true)
    private String batteryId;
    // 电压
    @Column
    private Double voltage;
    // 电流
    @Column
    private Double current;
    // 温度
    @Column
    private Double temperature;
    // 剩余电量
    @Column
    private Double soc;
    // 时间戳(InfluxDB 核心)
    @Column(timestamp = true)
    private Instant time;


}
