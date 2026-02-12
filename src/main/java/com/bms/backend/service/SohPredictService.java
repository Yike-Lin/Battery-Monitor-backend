package com.bms.backend.service;

import com.bms.backend.exception.BusinessException;
import com.bms.backend.storage.ObjectStorageService;
import lombok.Data;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 从 MinIO 读取 CSV，按某个 Cycle 提取时间序列，
 * 重采样为 128 点，发给 Python /predict，返回 SOH。
 */
@Service
public class SohPredictService {

    private final ObjectStorageService objectStorageService;
    private final RestTemplate restTemplate;

    // Python Flask 服务地址
    private final String pythonBaseUrl = "http://localhost:5000";

    // Python 预期长度
    private static final int TARGET_LEN = 128;

    public SohPredictService(ObjectStorageService objectStorageService,
                             RestTemplate restTemplate) {
        this.objectStorageService = objectStorageService;
        this.restTemplate = restTemplate;
    }

    /**
     * 根据 CSV 的 fileKey + 想用的cycle 号，预测 SOH
     */
    public Double predictSohFromCsvFileKey(String fileKey, int cycleForPredict) {
        if (fileKey == null || fileKey.trim().isEmpty()) {
            throw new BusinessException("fileKey 不能为空，无法进行 SOH 预测");
        }

        List<Double> timeList = new ArrayList<>();
        List<Double> currentList = new ArrayList<>();
        List<Double> voltageList = new ArrayList<>();
        List<Double> tempList = new ArrayList<>();

        try (InputStream in = objectStorageService.downloadCsv(fileKey);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String header = reader.readLine();
            if (header == null) {
                throw new BusinessException("CSV 文件为空");
            }

            // 解析表头：Cycle,Time_Min,Voltage,Current,Temp,Capacity
            String[] headers = header.split(",");
            int idxCycle = -1, idxTime = -1, idxVoltage = -1, idxCurrent = -1, idxTemp = -1;

            for (int i = 0; i < headers.length; i++) {
                String col = headers[i].trim().toLowerCase();
                if (col.equals("cycle")) {
                    idxCycle = i;
                } else if (col.equals("time_min")) {
                    idxTime = i;
                } else if (col.equals("voltage")) {
                    idxVoltage = i;
                } else if (col.equals("current")) {
                    idxCurrent = i;
                } else if (col.equals("temp")) {
                    idxTemp = i;
                }
            }

            if (idxCycle < 0 || idxTime < 0 || idxVoltage < 0 || idxCurrent < 0 || idxTemp < 0) {
                throw new BusinessException("CSV 表头不符合预期: " + header);
            }

            // 读取行：只保留 Cycle == cycleForPredict 的行
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",");
                int maxIdx = Math.max(
                        idxCycle,
                        Math.max(Math.max(idxTime, idxVoltage), Math.max(idxCurrent, idxTemp))
                );
                if (parts.length <= maxIdx) continue;

                try {
                    int cycle = Integer.parseInt(parts[idxCycle].trim());
                    if (cycle != cycleForPredict) {
                        continue; // 只保留指定循环
                    }

                    double t = Double.parseDouble(parts[idxTime].trim());
                    double v = Double.parseDouble(parts[idxVoltage].trim());
                    double c = Double.parseDouble(parts[idxCurrent].trim());
                    double tp = Double.parseDouble(parts[idxTemp].trim());

                    timeList.add(t);
                    voltageList.add(v);
                    currentList.add(c);
                    tempList.add(tp);
                } catch (NumberFormatException e) {
                    continue;
                }
            }

        } catch (IOException e) {
            throw new BusinessException("读取 CSV 失败: " + e.getMessage());
        }

        if (timeList.isEmpty()) {
            throw new BusinessException("CSV 中未找到 cycle=" + cycleForPredict + " 的数据");
        }

        // 重采样到 128 点
        double[] timeArr = resampleToFixedLength(timeList, TARGET_LEN);
        double[] currentArr = resampleToFixedLength(currentList, TARGET_LEN);
        double[] voltageArr = resampleToFixedLength(voltageList, TARGET_LEN);
        double[] tempArr = resampleToFixedLength(tempList, TARGET_LEN);

        // 调 Python /predict
        Map<String, Object> payload = new HashMap<>();
        payload.put("time", timeArr);
        payload.put("current", currentArr);
        payload.put("voltage", voltageArr);
        payload.put("temp", tempArr);

        String url = pythonBaseUrl + "/predict";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        ResponseEntity<PythonResp> resp = restTemplate.exchange(url, HttpMethod.POST, entity, PythonResp.class);

        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new BusinessException("调用 SOH 预测服务失败: HTTP=" + resp.getStatusCode());
        }

        PythonResp body = resp.getBody();
        if (body.getCode() != 200 || body.getData() == null) {
            throw new BusinessException("SOH 服务返回错误: " + body.getMsg());
        }

        Double soh = body.getData().getSoh();
        if (soh == null) {
            throw new BusinessException("SOH 服务未返回 soh 字段");
        }
        return soh;
    }

    /**
     * 线性插值将任意长度序列 resample 到 targetLen
     */
    private double[] resampleToFixedLength(List<Double> source, int targetLen) {
        double[] result = new double[targetLen];
        int n = source.size();

        if (n == 0) {
            Arrays.fill(result, 0.0);
            return result;
        }
        if (n == 1) {
            Arrays.fill(result, source.get(0));
            return result;
        }
        if (n == targetLen) {
            for (int i = 0; i < targetLen; i++) {
                result[i] = source.get(i);
            }
            return result;
        }

        // 在 [0, n-1] 上等间距采样 targetLen 个点
        for (int i = 0; i < targetLen; i++) {
            double pos = ((double) i / (targetLen - 1)) * (n - 1);
            int i0 = (int) Math.floor(pos);
            int i1 = Math.min(i0 + 1, n - 1);
            double t = pos - i0;
            double v0 = source.get(i0);
            double v1 = source.get(i1);
            result[i] = v0 * (1 - t) + v1 * t;
        }
        return result;
    }

    // Python 返回 JSON 的 DTO

    @Data
    public static class PythonResp {
        private int code;
        private String msg;
        private PythonData data;
    }

    @Data
    public static class PythonData {
        private Double soh;
        private String health_status;
    }
}