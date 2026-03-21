package com.bms.backend.service;

import com.bms.backend.storage.ObjectStorageService;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV -> InfluxDB 的写入服务（上传 CSV 后自动写入 Influx）
 */
@Service
public class InfluxCsvIngestService {
    private static final Logger log = LoggerFactory.getLogger(InfluxCsvIngestService.class);

    private static final String MEASUREMENT = "battery_metrics";
    private static final int BATCH_SIZE = 500;
    private static final int SLEEP_MS_BETWEEN_BATCH = 50;

    @Autowired
    private InfluxDBClient influxDBClient;

    @Autowired
    private ObjectStorageService objectStorageService;

    @Value("${influxdb.bucket}")
    private String bucket;

    @Value("${influxdb.org}")
    private String org;

    /**
     * 异步写入：把 MinIO 的 CSV 解析后写到 InfluxDB。
     *
     * @param fileKey MinIO 文件 key
     * @param cellId 作为 Influx tag 的 cell_id（batteryCode）
     * @param batchId 作为 Influx tag 的 batch_id（uploadToken）
     */
    @Async
    public void ingestFromMinioCsv(String fileKey, String cellId, String batchId) {
        if (fileKey == null || fileKey.trim().isEmpty()) return;
        if (cellId == null || cellId.trim().isEmpty()) return;
        if (batchId == null || batchId.trim().isEmpty()) batchId = "batch";

        // 先做一次预扫描拿到 maxTimeMin
        Double maxTimeMin = scanMaxTimeMin(fileKey);
        if (maxTimeMin == null || maxTimeMin <= 0) {
            maxTimeMin = 0.0;
        }

        Instant endInstant = Instant.now();
        Instant startInstant = endInstant.minusSeconds((long) (maxTimeMin * 60));

        // 第二次解析并写入
        try (InputStream in = objectStorageService.downloadCsv(fileKey);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                log.warn("⚠️ CSV 空文件：fileKey={}", fileKey);
                return;
            }

            String[] headers = splitHeader(headerLine);
            int idxCycle = indexOfIgnoreCase(headers, "cycle");
            int idxTime = indexOfIgnoreCase(headers, "time_min");
            int idxVoltage = indexOfIgnoreCase(headers, "voltage");
            int idxCurrent = indexOfIgnoreCase(headers, "current");
            int idxTemp = indexOfIgnoreCase(headers, "temp");
            int idxCapacity = indexOfIgnoreCase(headers, "capacity");

            if (idxTime < 0 || idxVoltage < 0 || idxCurrent < 0 || idxCycle < 0) {
                log.warn("⚠️ CSV 表头不符合预期：fileKey={}, headers={}", fileKey, headerLine);
                return;
            }

            List<Point> batchPoints = new ArrayList<>();
            long written = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                String[] parts = splitRow(line);
                int maxIdx = Math.max(
                        idxCycle,
                        Math.max(Math.max(idxTime, idxVoltage), Math.max(idxCurrent, Math.max(idxTemp, idxCapacity)))
                );
                if (parts.length <= maxIdx) continue;

                try {
                    int cycle = Integer.parseInt(parts[idxCycle].trim());
                    double timeMin = Double.parseDouble(parts[idxTime].trim());
                    double voltage = Double.parseDouble(parts[idxVoltage].trim());
                    double current = Double.parseDouble(parts[idxCurrent].trim());
                    double temp = idxTemp >= 0 ? parseDoubleSafe(parts[idxTemp].trim(), 0.0) : 0.0;
                    double capacity = idxCapacity >= 0 ? parseDoubleSafe(parts[idxCapacity].trim(), 0.0) : 0.0;

                    Instant pointTime = startInstant.plusSeconds((long) (timeMin * 60));

                    Point point = Point.measurement(MEASUREMENT)
                            .addTag("cell_id", cellId)
                            .addTag("batch_id", batchId)
                            .addTag("cycle_index", String.valueOf(cycle))
                            .addField("voltage", voltage)
                            .addField("current", current)
                            .addField("temperature", temp)
                            .addField("capacity", capacity)
                            .time(pointTime, WritePrecision.MS);

                    batchPoints.add(point);
                    if (batchPoints.size() >= BATCH_SIZE) {
                        writeBatch(batchPoints);
                        written += batchPoints.size();
                        batchPoints.clear();
                        Thread.sleep(SLEEP_MS_BETWEEN_BATCH);
                    }
                } catch (Exception ignored) {
                    // 忽略单行格式错误，保证整体可以继续写入
                }
            }

            if (!batchPoints.isEmpty()) {
                writeBatch(batchPoints);
                written += batchPoints.size();
            }

            log.info("✅ Influx 写入完成：fileKey={}, cell_id={}, batch_id={}, points={}",
                    fileKey, cellId, batchId, written);
        } catch (Exception e) {
            log.error("❌ Influx 写入失败：fileKey={}, err={}", fileKey, e.getMessage());
        }
    }

    private void writeBatch(List<Point> batchPoints) {
        // 使用阻塞写入，确保点确实进入 Influx（便于大屏立即可见）
        WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();
        writeApi.writePoints(bucket, org, batchPoints);
    }

    private Double scanMaxTimeMin(String fileKey) {
        try (InputStream in = objectStorageService.downloadCsv(fileKey);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) return null;
            String[] headers = splitHeader(headerLine);

            int idxTime = indexOfIgnoreCase(headers, "time_min");
            if (idxTime < 0) return null;

            double max = 0.0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = splitRow(line);
                if (parts.length <= idxTime) continue;

                try {
                    double timeMin = Double.parseDouble(parts[idxTime].trim());
                    if (timeMin > max) max = timeMin;
                } catch (Exception ignored) {
                }
            }
            return max;
        } catch (Exception e) {
            log.warn("⚠️ 预扫描 maxTimeMin 失败：fileKey={}, err={}", fileKey, e.getMessage());
            return null;
        }
    }

    private static String[] splitHeader(String line) {
        if (line == null) return new String[0];
        String[] comma = line.split(",");
        if (comma.length >= 6) return comma;
        return line.split("\t");
    }

    private static String[] splitRow(String line) {
        if (line == null) return new String[0];
        String[] comma = line.split(",");
        if (comma.length >= 6) return comma;
        return line.split("\t");
    }

    private static int indexOfIgnoreCase(String[] arr, String target) {
        if (arr == null || target == null) return -1;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == null) continue;
            String v = arr[i].trim().toLowerCase();
            if (v.equals(target.toLowerCase())) return i;
        }
        return -1;
    }

    private static double parseDoubleSafe(String s, double defaultVal) {
        try {
            return Double.parseDouble(s);
        } catch (Exception ignored) {
            return defaultVal;
        }
    }
}

