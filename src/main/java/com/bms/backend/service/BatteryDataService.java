package com.bms.backend.service;

import com.bms.backend.entity.DashboardData;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class BatteryDataService {

    private static final Logger log = LoggerFactory.getLogger(BatteryDataService.class);

    @Autowired
    private InfluxDBClient influxDBClient;

    @Value("${influxdb.bucket}")
    private String bucket;

    @Value("${influxdb.org}")
    private String org;

    // 时间格式化器：转成前端需要的 "HH:mm:ss"
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /**
     * 获取双通道实时数据
     * @param idA Pack A 的电池ID (例如 b1c0)
     * @param idB Pack B 的电池ID (例如 b1c1)
     */
    public DashboardData getDashboardStream(String idA, String idB) {
        DashboardData data = new DashboardData();

        // 1. 构造 Flux 查询
        // 逻辑：在过去 1 分钟内，找出 idA 或 idB 的 voltage/current 数据，取最后一条，并把字段转为列
        String query = String.format(
                "from(bucket: \"%s\") " +
                        "|> range(start: -1m) " +
                        "|> filter(fn: (r) => r[\"_measurement\"] == \"battery_metrics\") " +
                        "|> filter(fn: (r) => r[\"cell_id\"] == \"%s\" or r[\"cell_id\"] == \"%s\") " +
                        "|> filter(fn: (r) => r[\"_field\"] == \"voltage\" or r[\"_field\"] == \"current\") " +
                        "|> last() " +
                        "|> pivot(rowKey:[\"_time\", \"cell_id\"], columnKey: [\"_field\"], valueColumn: \"_value\")",
                bucket, idA, idB);

        try {
            // 2. 执行查询
            List<FluxTable> tables = influxDBClient.getQueryApi().query(query, org);

            // 3. 解析结果
            boolean hasData = false;
            Instant latestTime = null;

            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    hasData = true;
                    String cellId = (String) record.getValueByKey("cell_id");

                    // 安全获取数值 (防止 null 或类型转换错误)
                    double v = getDoubleValue(record.getValueByKey("voltage"));
                    double c = getDoubleValue(record.getValueByKey("current"));

                    // 记录最新的时间戳 (用于X轴)
                    if (record.getTime() != null) {
                        if (latestTime == null || record.getTime().isAfter(latestTime)) {
                            latestTime = record.getTime();
                        }
                    }

                    // --- 核心映射逻辑 ---
                    if (idA.equals(cellId)) {
                        // 如果读到的是 b1c0，填入 Pack A
                        data.setVa(v);
                        data.setCa(c);
                    } else if (idB.equals(cellId)) {
                        // 如果读到的是 b1c1，填入 Pack B
                        data.setVb(v);
                        data.setCb(c);
                    }
                }
            }

            // 4. 设置时间 (如果没有查到数据，使用当前时间，防止前端报错)
            if (latestTime != null) {
                data.setTime(timeFormatter.format(latestTime));
            } else {
                data.setTime(timeFormatter.format(Instant.now()));
                // 只有调试时开启，防止刷屏
                // log.debug("⚠️ 未查询到实时数据，使用系统当前时间填充。检查模拟器是否开启？");
            }

        } catch (Exception e) {
            log.error("❌ 查询 InfluxDB 失败: {}", e.getMessage());
            // 出错时也返回当前时间，保证前端不白屏
            data.setTime(timeFormatter.format(Instant.now()));
        }

        return data;
    }

    // 辅助方法：安全转 Double
    private double getDoubleValue(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }
}