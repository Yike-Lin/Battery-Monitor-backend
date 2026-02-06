package com.bms.backend.service;


import cn.hutool.core.text.replacer.StrReplacer;
import com.bms.backend.dto.BatteryDraftDto;
import com.bms.backend.entity.Battery;
import com.bms.backend.entity.BatteryCsvUpload;
import com.bms.backend.entity.BatteryRecord;
import com.bms.backend.exception.BusinessException;
import com.bms.backend.repository.BatteryCsvUploadRepository;
import com.bms.backend.repository.BatteryRecordRepository;
import com.bms.backend.repository.BatteryRepository;
import com.bms.backend.storage.ObjectStorageService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
    private final BatteryCsvUploadRepository uploadRepository;
    private final ObjectStorageService objectStorageService;

    public BatteryCsvService(BatteryRepository batteryRepository,
                             BatteryRecordRepository batteryRecordRepository, BatteryCsvUploadRepository batteryCsvUploadRepository, ObjectStorageService objectStorageService) {
        this.batteryRepository = batteryRepository;
        this.batteryRecordRepository = batteryRecordRepository;
        this.uploadRepository = batteryCsvUploadRepository;
        this.objectStorageService = objectStorageService;
    }

    /**
     * 解析CSV出来一个电池台账草稿(并把CSV本体存对象存储，元数据存PG）
     * @param file
     * @return draft
     * @throws IOException
     */
    public BatteryDraftDto parseCsvToDraft(MultipartFile file) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件为空");
        }

        // 1. 生成唯一标识uploadToken
        String uploadToken = UUID.randomUUID().toString().replace("-", "");

        // 2. 将文件上传到MinIO，返回fileKey
        String fileKey = objectStorageService.uploadCsv(uploadToken, file);

        // 3. 在PG中保存上传记录元数据
        BatteryCsvUpload upload = new BatteryCsvUpload();
        upload.setUploadToken(uploadToken);
        upload.setFileName(file.getOriginalFilename());
        upload.setFileSize(file.getSize());
        upload.setContentType(file.getContentType());
        upload.setFileKey(fileKey);
        upload.setStatus("NEW");
        upload.setCreatedAt(OffsetDateTime.now());
        uploadRepository.save(upload);

        // 4. 轻量化解析，计算Cycle和行数
        int cycleCol = -1;              // Cycle列的索引位置
        long dataRowCount = 0;          // 有效数据行数
        Integer maxCycle = null;        // 记录最大的Cycle

        try(InputStream in = objectStorageService.downloadCsv(fileKey);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in , StandardCharsets.UTF_8))) {

            Map<String, Integer> headerIndex = new HashMap<>();
            String line;                // 暂存读取到的每一行文字
            boolean isFirstLine = true; // 标记是否第一行

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split(",");

                // 处理表头
                if (isFirstLine) {
                    isFirstLine = false;
                    // 建立列名——>索引的映射
                    for (int i = 0; i <parts.length; i++) {
                        String colName = parts[i].trim();
                        headerIndex.put(colName , i);
                    }
                    // 尝试定位一下“Cycle列”
                    if(headerIndex.containsKey("Cycle")) {
                        cycleCol = headerIndex.get("Cycle");
                    }
                    continue;
                }
                // 处理数据行
                dataRowCount++;

                // 提取并统计Cycle最大值
                if (cycleCol >= 0 && cycleCol < parts.length) {
                    String cycleStr = parts[cycleCol].trim();
                    if (!cycleStr.isEmpty()) {
                        try {
                            int cycle = Integer.parseInt(cycleStr);
                            // 擂台法维护Cycle最大值
                            if (maxCycle == null || cycle > maxCycle) {
                                maxCycle = cycle;
                            }
                        }catch (NumberFormatException ignored) {
                            // 跳过这行脏数据
                        }
                    }
                }

            }
        }
        int cycleCount;
        if (maxCycle != null) {
            cycleCount = maxCycle;
        } else {
            cycleCount = (int) dataRowCount;
        }

        // 5. 回填统计信息
        upload.setRowCount(dataRowCount);
        upload.setCycleCount(cycleCount);
        uploadRepository.save(upload);

        // 6. 构造草稿DTO
        BatteryDraftDto draft = new BatteryDraftDto();
        draft.setBatteryCode(null);
        draft.setModelCode(null);
        draft.setCustomerName(null);
        draft.setRatedCapacityAh(null);
        draft.setCycleCount(cycleCount);
        draft.setSohPercent(null);
        draft.setLastRecordAt(OffsetDateTime.now());
        draft.setUploadToken(uploadToken);

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
