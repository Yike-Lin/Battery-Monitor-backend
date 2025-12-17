package com.bms.backend.service;

import com.bms.backend.entity.BatteryData;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
// 1. 引入标准的日志包
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
// @Slf4j  <-- 删掉这个
public class InfluxDbService {

    // 2. 手动创建日志对象 (这一行代替了 @Slf4j)
    private static final Logger log = LoggerFactory.getLogger(InfluxDbService.class);

    @Autowired
    private InfluxDBClient influxDBClient;

    @Value("${influxdb.bucket}")
    private String bucket;

    @Value("${influxdb.org}")
    private String org;

    public void writeOne(BatteryData data) {
        try {
            WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();
            writeApi.writeMeasurement(bucket, org, WritePrecision.NS, data);

            // 下面这行代码不用动，因为我们上面定义的变量名就叫 log
            log.info("✅ 写入成功 -> ID: {}, V: {}", data.getBatteryId(), data.getVoltage());

        } catch (Exception e) {
            log.error("❌ 写入 InfluxDB 失败", e);
        }
    }
}