package com.bms.backend.service;


import com.bms.backend.dto.BatteryDraftDto;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CSV文件解析业务
 */
@Service
public class BatteryCsvService {

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

}
