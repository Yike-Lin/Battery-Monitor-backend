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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                        "|> range(start: -30d) " +
                        "|> filter(fn: (r) => r[\"_measurement\"] == \"battery_metrics\") " +
                        "|> filter(fn: (r) => r[\"cell_id\"] == \"%s\" or r[\"cell_id\"] == \"%s\") " +
                        "|> filter(fn: (r) => r[\"_field\"] == \"voltage\" or r[\"_field\"] == \"current\") " +
                        "|> last() " +
                        "|> pivot(rowKey:[\"_time\", \"cell_id\"], columnKey: [\"_field\"], valueColumn: \"_value\")",
                bucket, idA, idB);

        try {
            // 返回给前端用于显示
            data.setCellIdA(idA);
            data.setCellIdB(idB);

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

    /**
     * 自动选择 InfluxDB 中“最新采样”的电池 cell_id（取最新两块）作为双通道数据源。
     * 前端只要调用 /api/battery-dashboard/stream 不传 idA/idB，就会走这里。
     */
    public DashboardData getDashboardStreamLatestTwo() {
        DashboardData data = new DashboardData();

        try {
            // 1) 取最新的 2 个 cell_id（用 voltage 的 last() 作为采样时间依据）
            String queryLatestCells = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -30d) " +
                            "|> filter(fn: (r) => r[\"_measurement\"] == \"battery_metrics\") " +
                            "|> filter(fn: (r) => r[\"_field\"] == \"voltage\") " +
                            "|> group(columns: [\"cell_id\"]) " +
                            "|> last() " +
                            "|> keep(columns: [\"cell_id\", \"_time\"]) " +
                            "|> sort(columns: [\"_time\"], desc: true) " +
                            "|> limit(n: 2)",
                    bucket
            );

            List<FluxTable> tables = influxDBClient.getQueryApi().query(queryLatestCells, org);

            String idA = null;
            String idB = null;
            Instant latestTime = null;

            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    String cellId = (String) record.getValueByKey("cell_id");
                    if (cellId == null) continue;

                    if (idA == null) {
                        idA = cellId;
                    } else if (idB == null && !cellId.equals(idA)) {
                        idB = cellId;
                    }

                    if (record.getTime() != null) {
                        if (latestTime == null || record.getTime().isAfter(latestTime)) {
                            latestTime = record.getTime();
                        }
                    }
                }
            }

            // 如果只查到 1 块，就让 B= A，保证前端不报空
            if (idA == null) {
                data.setTime(timeFormatter.format(Instant.now()));
                return data;
            }
            if (idB == null) idB = idA;

            // 返回给前端用于显示
            data.setCellIdA(idA);
            data.setCellIdB(idB);

            // 2) 查这两个 cell_id 的最后电压/电流，pivot 成列
            String queryLatestVaCa = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -30d) " +
                            "|> filter(fn: (r) => r[\"_measurement\"] == \"battery_metrics\") " +
                            "|> filter(fn: (r) => r[\"cell_id\"] == \"%s\" or r[\"cell_id\"] == \"%s\") " +
                            "|> filter(fn: (r) => r[\"_field\"] == \"voltage\" or r[\"_field\"] == \"current\") " +
                            "|> last() " +
                            "|> pivot(rowKey:[\"_time\", \"cell_id\"], columnKey: [\"_field\"], valueColumn: \"_value\")",
                    bucket, idA, idB
            );

            List<FluxTable> tables2 = influxDBClient.getQueryApi().query(queryLatestVaCa, org);

            for (FluxTable table : tables2) {
                for (FluxRecord record : table.getRecords()) {
                    String cellId = (String) record.getValueByKey("cell_id");
                    if (cellId == null) continue;

                    double v = getDoubleValue(record.getValueByKey("voltage"));
                    double c = getDoubleValue(record.getValueByKey("current"));

                    if (record.getTime() != null) {
                        if (latestTime == null || record.getTime().isAfter(latestTime)) {
                            latestTime = record.getTime();
                        }
                    }

                    if (idA.equals(cellId)) {
                        data.setVa(v);
                        data.setCa(c);
                    } else if (idB.equals(cellId)) {
                        data.setVb(v);
                        data.setCb(c);
                    }
                }
            }

            data.setTime(latestTime != null ? timeFormatter.format(latestTime) : timeFormatter.format(Instant.now()));
            return data;
        } catch (Exception e) {
            log.error("❌ 查询 InfluxDB 最新电池失败: {}", e.getMessage());
            data.setTime(timeFormatter.format(Instant.now()));
            return data;
        }
    }

    /**
     * 获取所有 cell_id 的最新电压/温度（来自 InfluxDB measurement=battery_metrics）。
     * 说明：temperature 字段名由模拟/导入数据写入，非 Temp。
     */
    public Map<String, LatestVt> getLatestVoltageTemperatureByCellId() {
        Map<String, LatestVt> map = new HashMap<>();
        String query = String.format(
                "from(bucket: \"%s\") " +
                        "|> range(start: -180d) " +
                        "|> filter(fn: (r) => r[\"_measurement\"] == \"battery_metrics\") " +
                        "|> filter(fn: (r) => r[\"_field\"] == \"voltage\" or r[\"_field\"] == \"temperature\") " +
                        "|> group(columns: [\"cell_id\", \"_field\"]) " +
                        "|> last() " +
                        "|> keep(columns: [\"cell_id\", \"_field\", \"_value\", \"_time\"])",
                bucket
        );

        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(query, org);
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    String cellId = (String) record.getValueByKey("cell_id");
                    if (cellId == null) continue;
                    String field = String.valueOf(record.getValueByKey("_field"));
                    LatestVt vt = map.computeIfAbsent(cellId.toLowerCase(), k -> new LatestVt());
                    if ("voltage".equals(field)) {
                        vt.setVoltage(getDoubleValue(record.getValue()));
                    } else if ("temperature".equals(field)) {
                        vt.setTemperature(getDoubleValue(record.getValue()));
                    }
                    if (record.getTime() != null) {
                        if (vt.getTime() == null || record.getTime().isAfter(vt.getTime())) {
                            vt.setTime(record.getTime());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ 查询 InfluxDB 最新 voltage/temperature 失败: {}", e.getMessage());
        }
        return map;
    }

    // 辅安全转 Double
    private double getDoubleValue(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    public static class LatestVt {
        private double voltage;
        private double temperature;
        private Instant time;

        public double getVoltage() {
            return voltage;
        }

        public void setVoltage(double voltage) {
            this.voltage = voltage;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public Instant getTime() {
            return time;
        }

        public void setTime(Instant time) {
            this.time = time;
        }
    }
}