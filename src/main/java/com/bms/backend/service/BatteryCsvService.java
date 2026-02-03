package com.bms.backend.service;


import com.bms.backend.dto.BatteryDraftDto;
import com.bms.backend.entity.Battery;
import com.bms.backend.entity.BatteryRecord;
import com.bms.backend.exception.BusinessException;
import com.bms.backend.repository.BatteryRecordRepository;
import com.bms.backend.repository.BatteryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StreamCorruptedException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * CSV文件解析业务
 */
@Service
public class BatteryCsvService {

    private final BatteryRepository batteryRepository;
    private final BatteryRecordRepository batteryRecordRepository;

    public BatteryCsvService(BatteryRepository batteryRepository,
                             BatteryRecordRepository batteryRecordRepository) {
        this.batteryRepository = batteryRepository;
        this.batteryRecordRepository = batteryRecordRepository;
    }

    /**
     * 解析CSV出来一个电池台账草稿
     * @param file
     * @return
     * @throws IOException
     */
    public BatteryDraftDto parseCsvToDraft(MultipartFile file) throws IOException {
        // 记录列名 --> 列索引 映射
        Map<String, Integer> headerIndex = new HashMap<>();

        int cycleCol = -1;
        int timeCol = -1;

        // 记录最大Cycle数
        Integer maxCycle = null;
        // 有效数据行数量
        long dataRowCount = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            boolean isFirstLine = true;

            while ((line = reader.readLine()) != null) {
                // 去掉首尾空白
                line = line.trim();
                if (line.isEmpty()) {
                    continue; // 跳过空行
                }

                // 简单按逗号分割
                String[] parts = line.split(",");

                // 第一行：表头
                if (isFirstLine) {
                    isFirstLine = false;

                    for (int i = 0; i < parts.length; i++) {
                        String colName = parts[i].trim();
                        headerIndex.put(colName, i);
                    }

                    // 尝试找到几个关键列
                    if (headerIndex.containsKey("Cycle")) {
                        cycleCol = headerIndex.get("Cycle");
                    }
                    if (headerIndex.containsKey("Time_Min")) {
                        timeCol = headerIndex.get("Time_Min");
                    }

                    // 如果没有 Cycle 列，就用行数估算
                    continue;
                }

                // 普通数据行
                dataRowCount++;

                // 如果有 Cycle 列，解析它
                if (cycleCol >= 0 && cycleCol < parts.length) {
                    String cycleStr = parts[cycleCol].trim();
                    if (!cycleStr.isEmpty()) {
                        try {
                            int cycle = Integer.parseInt(cycleStr);
                            if (maxCycle == null || cycle > maxCycle) {
                                maxCycle = cycle;
                            }
                        } catch (NumberFormatException e) {
                            // 有脏数据，就忽略这个 cycle
                            // 这里先不抛异常，尽量从其它行正常数据里统计
                        }
                    }
                }

                // 有绝对时间列，再解析 timeCol，更新 lastRecordAt
            }
        }


        // 计算 cycleCount
        int cycleCount;
        if (maxCycle != null) {
            cycleCount = maxCycle;
        } else {
            // 没有 Cycle 列或完全解析不到，就用数据行数估算一下
            cycleCount = (int) dataRowCount;
        }

        // 构造草稿 DTO
        BatteryDraftDto draft = new BatteryDraftDto();
        draft.setBatteryCode(null);
        draft.setModelCode(null);
        draft.setCustomerName(null);
        draft.setRatedCapacityAh(null);

        draft.setCycleCount(cycleCount);
        draft.setSohPercent(null);      // 暂不从 CSV 算
        draft.setLastRecordAt(OffsetDateTime.now()); // 先用当前时间占位

        draft.setUploadToken(UUID.randomUUID().toString());

        return draft;
    }


    /**
     * CSV文件导入（每一条BatteryRecord）
     * @param batteryId
     * @param file
     * @param uploadBatch
     */
    public void importBatteryRecordsFromCsv(Long batteryId , MultipartFile file , String uploadBatch) {
        // 1. 校验电池是否存在
        Battery battery = batteryRepository.findById(batteryId)
                .orElseThrow(() -> new BusinessException("电池不存在，id = " + batteryId));

        List<BatteryRecord> records = new ArrayList<>();

        // 2. 读取文件流
        try(BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(),StandardCharsets.UTF_8))) {

            String line;
            boolean isFirstLine = true;

            // 3. 逐行读CSV内容
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(",");

                // 4. 处理表头
                if (isFirstLine) {
                    // 标题列，不存库
                    isFirstLine = false;
                    continue;
                }

                // 5. 校验列数（转出来的CSV至少六列）
                if (parts.length < 6) {
                    continue;
                }

                // 6. 数据解析
                Integer cycle = parseIntOrNull(parts[0]);
                Double timeMin = parseDoubleOrNull(parts[1]);
                Double voltage = parseDoubleOrNull(parts[2]);
                Double current = parseDoubleOrNull(parts[3]);
                Double temp = parseDoubleOrNull(parts[4]);
                Double capacity = parseDoubleOrNull(parts[5]);

                // 7. 关键数据解析失败，则认为该行无效直接跳
                if (cycle == null || timeMin == null || voltage == null || current == null) {
                    continue;
                }

                // 8. 构建实体对象
                BatteryRecord record = BatteryRecord.builder()
                        .battery(battery)
                        .cycle(cycle)
                        .timeMin(timeMin)
                        .voltage(voltage)
                        .current(current)
                        .temp(temp)
                        .capacity(capacity)
                        .sourceFile(file.getOriginalFilename())
                        .uploadBatch(uploadBatch)
                        .build();

                records.add(record);
            }

        } catch (IOException e) {
            throw new BusinessException("CSV解析失败：" + e.getMessage());
        }

        // 9. 批量入库
        if (!records.isEmpty()) {
            batteryRecordRepository.saveAll(records);
        }

    }

    /**
     * 解析整数
     * @param s
     * @return
     */
    private Integer parseIntOrNull(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s);
        }catch (NumberFormatException e){
            return null;
        }
    }

    /**
     * 解析浮点数，解析失败返回null
     * @param s
     * @return
     */
    private Double parseDoubleOrNull(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        }catch (NumberFormatException e){
            return null;
        }
    }






























}
