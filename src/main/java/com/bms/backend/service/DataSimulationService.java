package com.bms.backend.service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.FileReader;
import java.io.Reader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 电池数据模拟服务
 */
@Service
public class DataSimulationService {

    // 使用 Logger 替代 System.out，方便看线程名
    private static final Logger log = LoggerFactory.getLogger(DataSimulationService.class);

    @Autowired
    private InfluxDBClient influxDBClient;

    @Value("${influxdb.bucket}")
    private String bucket;

    @Value("${influxdb.org}")
    private String org;

    // 全局停止开关 (volatile 保证多线程可见性)
    private volatile boolean isRunning = true; // 默认为 true，随时准备接收任务

    @Async
    public void startSimulation(String filePath, String targetCellId) {
        // 确保启动时开关是开的 (防止之前点过 Stop 导致无法再启动)
        isRunning = true;

        log.info("🚀 线程启动 -> 电池: {} | 文件: {}", targetCellId, filePath);

        try (Reader in = new FileReader(filePath)) {
            CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .build();

            Iterable<CSVRecord> records = csvFormat.parse(in);
            WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();
            List<Point> batchPoints = new ArrayList<>();

            Instant simulationStartTime = Instant.now();
            int count = 0;

            for (CSVRecord record : records) {
                // 检查全局停止开关
                if (!isRunning) {
                    log.warn("🛑 [{}] 检测到全局停止指令，线程中断。", targetCellId);
                    break;
                }

                try {
                    // 解析数据
                    double voltage = Double.parseDouble(record.get("Voltage"));
                    double current = Double.parseDouble(record.get("Current"));
                    double temp = Double.parseDouble(record.get("Temp"));
                    double capacity = Double.parseDouble(record.get("Capacity"));
                    double timeMin = Double.parseDouble(record.get("Time_Min"));
                    int cycle = Integer.parseInt(record.get("Cycle"));

                    Instant pointTime = simulationStartTime.plusSeconds((long) (timeMin * 60));

                    Point point = Point.measurement("battery_metrics")
                            .addTag("cell_id", targetCellId)
                            .addTag("batch_id", "batch-1") // 如果需要动态批次，也可以作为参数传入
                            .addTag("cycle_index", String.valueOf(cycle))
                            .addField("voltage", voltage)
                            .addField("current", current)
                            .addField("temperature", temp)
                            .addField("capacity", capacity)
                            .time(pointTime, WritePrecision.MS);

                    batchPoints.add(point);

                    if (batchPoints.size() >= 500) {
                        writeApi.writePoints(bucket, org, batchPoints);
                        batchPoints.clear();

                        // 稍微减少日志频率，避免控制台刷屏太快
                        if (count % 2000 == 0) {
                            log.info("   -> [{}] 已写入 {} 条数据...", targetCellId, count);
                        }

                        Thread.sleep(50);
                    }
                    count++;
                } catch (NumberFormatException e) {
                    // 只有调试时才打印详细错误，避免刷屏
                    // log.debug("跳过格式错误行: {}", record.toString());
                } catch (Exception e) {
                    log.error("⚠️ 行解析未知错误: {}", e.getMessage());
                }
            }

            // 写入剩余数据
            if (!batchPoints.isEmpty()) {
                writeApi.writePoints(bucket, org, batchPoints);
            }

            log.info("✅ [{}] 模拟自然结束！共写入 {} 条数据。", targetCellId, count);

        } catch (Exception e) {
            log.error("❌ [{}] 文件读取或模拟过程失败: {}", targetCellId, e.getMessage());
        }

        // 4. 删除了 finally { isRunning = false; }
        // 只有用户主动点 Stop 按钮，才把 isRunning 设为 false。
        // 单个任务跑完，不应该影响其他正在跑的电池。
    }

    /**
     * 停止所有正在运行的模拟任务
     */
    public void stopSimulation() {
        this.isRunning = false; // 一键关闸
        log.info("🛑 已发送全局停止指令！所有模拟线程将在下一次循环检查时退出。");
    }
}