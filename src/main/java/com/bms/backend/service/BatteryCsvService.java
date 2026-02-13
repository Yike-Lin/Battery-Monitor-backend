package com.bms.backend.service;


import com.bms.backend.dto.BatteryDraftDto;
import com.bms.backend.dto.BatteryRecordDto;
import com.bms.backend.dto.LifecyclePointDto;
import com.bms.backend.entity.Battery;
import com.bms.backend.entity.BatteryCsvUpload;
import com.bms.backend.exception.BusinessException;
import com.bms.backend.repository.BatteryCsvUploadRepository;
import com.bms.backend.repository.BatteryRecordRepository;
import com.bms.backend.repository.BatteryRepository;
import com.bms.backend.storage.ObjectStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final SohPredictService sohPredictService;
    private final ObjectStorageService objectStorageService;

    public BatteryCsvService(BatteryRepository batteryRepository,
                             BatteryRecordRepository batteryRecordRepository, BatteryCsvUploadRepository batteryCsvUploadRepository, SohPredictService sohPredictService, ObjectStorageService objectStorageService) {
        this.batteryRepository = batteryRepository;
        this.batteryRecordRepository = batteryRecordRepository;
        this.uploadRepository = batteryCsvUploadRepository;
        this.sohPredictService = sohPredictService;
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

                String[] parts = splitColumns(line);

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
     * 单体电池详情页用：按电池ID和循环号，从CSV中解析记录列表
     * @param batteryId
     * @param cycle
     * @return result
     */
    public List<BatteryRecordDto> getRecordsByBatteryAndCycle(Long batteryId , Integer cycle) {
        if (batteryId == null || cycle == null) {
            throw new BusinessException("batteryId 和 cycle 不能为空");
        }

        if (!batteryRepository.existsById(batteryId)) {
            throw new BusinessException("电池不存在，id=" + batteryId);
        }

        // 找这个电池关联的上传记录（最新一条）
        BatteryCsvUpload upload = uploadRepository
                .findTopByBatteryIdOrderByUsedAtDesc(batteryId)
                .orElseThrow(() -> new BusinessException("该电池没有关联的CSV上传记录"));

        String fileKey = upload.getFileKey();
        if (fileKey == null || fileKey.trim().isEmpty()) {
            throw new BusinessException("上传记录缺少 fileKey，无法读取CSV");
        }

        List<BatteryRecordDto> result = new ArrayList<>();

        try (InputStream in = objectStorageService.downloadCsv(fileKey);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            boolean isFirstLine = true;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = splitColumns(line);

                // 表头
                if (isFirstLine) {
                    isFirstLine = false;
                    continue;
                }

                if (parts.length < 6) {
                    continue;
                }

                Integer c        = parseIntOrNull(parts[0]);
                Double timeMin   = parseDoubleOrNull(parts[1]);
                Double voltage   = parseDoubleOrNull(parts[2]);
                Double current   = parseDoubleOrNull(parts[3]);
                Double temp      = parseDoubleOrNull(parts[4]);
                Double capacity  = parseDoubleOrNull(parts[5]);

                if (c == null || timeMin == null || voltage == null || current == null) {
                    continue;
                }

                // 只取当前 cycle 的记录
                if (!c.equals(cycle)) {
                    continue;
                }

                BatteryRecordDto dto = new BatteryRecordDto();
                dto.setId(null);
                dto.setBatteryId(batteryId);
                dto.setCycle(c);
                dto.setTimeMin(timeMin);
                dto.setVoltage(voltage);
                dto.setCurrent(current);
                dto.setTemp(temp);
                dto.setCapacity(capacity);
                dto.setSourceFile(upload.getFileName());
                dto.setUploadBatch(upload.getUploadToken());

                result.add(dto);
            }

        } catch (IOException e) {
            throw new BusinessException("读取 CSV 失败：" + e.getMessage());
        }

        return result;
    }


    /**
     * 根据 uploadToken 把上传记录绑定到指定电池
     * 只更新 battery_csv_upload，不往 battery_record 表导数据
     */
    @Transactional
    public void bindUploadToBattery(Long batteryId, String uploadToken) {
        if (batteryId == null) {
            throw new BusinessException("batteryId 不能为空");
        }
        if (uploadToken == null || uploadToken.trim().isEmpty()) {
            throw new BusinessException("uploadToken 不能为空");
        }

        // 1. 确认电池是否存在
        Battery battery = batteryRepository.findById(batteryId)
                .orElseThrow(() -> new BusinessException("电池不存在，id = " + batteryId));

        // 2. 查上传记录
        BatteryCsvUpload upload = uploadRepository.findByUploadToken(uploadToken)
                .orElseThrow(() -> new BusinessException("上传记录不存在，token = " + uploadToken));

        upload.setBatteryId(batteryId);
        upload.setStatus("USED");                 // 表示已被某个电池使用
        upload.setUsedAt(OffsetDateTime.now());
        uploadRepository.save(upload);
    }


    /**
     * 全生命周期容量趋势：按电池ID解析CSV，按cycle汇总每轮容量
     * @param batteryId
     * @return （cycle, capacityAh）
     */
    public List<LifecyclePointDto> getLifecycleCapacityTrend(Long batteryId) {
        if (batteryId == null) {
            throw new BusinessException("batteryId 不能为空");
        }

        if (!batteryRepository.existsById(batteryId)) {
            throw new BusinessException("电池不存在，id=" + batteryId);
        }

        // 找这个电池关联的上传记录（最新一条）
        BatteryCsvUpload upload = uploadRepository
                .findTopByBatteryIdOrderByUsedAtDesc(batteryId)
                .orElseThrow(() -> new BusinessException("该电池没有关联的CSV上传记录"));

        String fileKey = upload.getFileKey();
        if (fileKey == null || fileKey.trim().isEmpty()) {
            throw new BusinessException("上传记录缺少 fileKey，无法读取CSV");
        }

        Map<Integer, Double> lastCapacityByCycle = new HashMap<>();

        try (InputStream in = objectStorageService.downloadCsv(fileKey);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            boolean isFirstLine = true;
            Map<String, Integer> headerIndex = new HashMap<>();

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = splitColumns(line);

                // 处理表头
                if (isFirstLine) {
                    isFirstLine = false;
                    for (int i = 0; i < parts.length; i++) {
                        String colName = parts[i].trim();
                        headerIndex.put(colName, i);
                    }
                    System.out.println("Lifecycle header index: " + headerIndex);
                    continue;
                }

                Integer cycleIdx    = headerIndex.get("Cycle");
                Integer capacityIdx = headerIndex.get("Capacity");
                if (cycleIdx == null || capacityIdx == null) {
                    throw new BusinessException("CSV 中缺少 Cycle 或 Capacity 列");
                }

                if (parts.length <= Math.max(cycleIdx, capacityIdx)) {
                    continue;
                }

                Integer c       = parseIntOrNull(parts[cycleIdx]);
                Double capacity = parseDoubleOrNull(parts[capacityIdx]);

                if (c == null) {
                    continue;
                }
                if (capacity != null) {
                    // 每次覆盖，最后留下这个 cycle 的“最后一条容量值”
                    lastCapacityByCycle.put(c, capacity);
                }
            }

        } catch (IOException e) {
            throw new BusinessException("读取 CSV 失败：" + e.getMessage());
        }

        // Map -> List，按 cycle 升序排序
        List<LifecyclePointDto> result = new ArrayList<>();
        lastCapacityByCycle.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    LifecyclePointDto dto = new LifecyclePointDto();
                    dto.setCycle(entry.getKey());
                    dto.setCapacityAh(entry.getValue());
                    result.add(dto);
                });

        return result;
    }


    /**
     * 根据uploadToken 预测SOH
     * @param uploadToken
     * @return
     */
    public Double predictSohByUploadToken(String uploadToken) {
        BatteryCsvUpload upload = uploadRepository.findByUploadToken(uploadToken)
                .orElseThrow(() -> new BusinessException("上传记录不存在，token = " + uploadToken));

        String fileKey = upload.getFileKey();
        if (fileKey == null || fileKey.trim().isEmpty()) {
            throw new BusinessException("上传记录缺少 fileKey，无法进行 SOH 预测");
        }

        Integer cycleCount = upload.getCycleCount();
        int cycleForPredict;
        if (cycleCount != null && cycleCount > 0) {
            cycleForPredict = cycleCount;
        } else {
            // 没有记录就退化成用第一个循环
            cycleForPredict = 1;
        }

        return sohPredictService.predictSohFromCsvFileKey(fileKey, cycleForPredict);
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

    /**
     * 按列拆分：优先尝试逗号，若列数不足，再尝试制表符
     */
    private String[] splitColumns(String line) {
        if (line == null) {
            return new String[0];
        }
        // 先按逗号
        String[] parts = line.split(",");
        if (parts.length >= 6) {
            return parts;
        }
        // 列数不够，再按制表符
        parts = line.split("\t");
        return parts;
    }


}
