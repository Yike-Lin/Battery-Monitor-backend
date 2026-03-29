package com.bms.backend.service;

import com.bms.backend.dto.IcAnalysisResponse;
import com.bms.backend.dto.IcPointDto;
import com.bms.backend.entity.Battery;
import com.bms.backend.entity.DashboardData;
import com.bms.backend.repository.BatteryRepository;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BatteryDataService {

    private static final Logger log = LoggerFactory.getLogger(BatteryDataService.class);

    @Autowired
    private InfluxDBClient influxDBClient;
    @Autowired
    private BatteryRepository batteryRepository;
    @Autowired
    private RealtimeSignalFilterService signalFilterService;

    @Value("${influxdb.bucket}")
    private String bucket;

    @Value("${influxdb.org}")
    private String org;

    @Value("${bms.influx.latest-range:-30d}")
    private String influxLatestRange;

    /** 与拓扑共用同一份 Influx 最新点，避免列表与 @Scheduled 各打一遍 */
    private volatile long latestVtcCacheAtMs = 0L;
    private volatile Map<String, LatestVtc> latestVtcCache = Collections.emptyMap();
    private final Object latestVtcCacheLock = new Object();
    private static final long LATEST_VTC_CACHE_TTL_MS = 2000L;

    // 时间格式化器：转成前端需要的 "HH:mm:ss"
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    // IC 主分析电压窗口（抑制两端噪声）
    private static final double IC_V_MIN = 3.60;
    private static final double IC_V_MAX = 4.20;
    // dQ/dV 裁剪上限（先粗裁，再做分位数裁剪）
    private static final double DQDV_ABS_CLIP = 30.0;

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
                    // 原始电压和电流
                    final double vRaw = v;
                    final double cRaw = c;
                    // 平滑电压和电流
                    if (cellId != null) {
                        String ck = cellId.toLowerCase();
                        v = signalFilterService.smoothVoltageReading(ck, vRaw);
                        c = signalFilterService.smoothCurrentReading(ck, cRaw);
                    }

                    // 记录最新的时间戳 (用于X轴)
                    if (record.getTime() != null) {
                        if (latestTime == null || record.getTime().isAfter(latestTime)) {
                            latestTime = record.getTime();
                        }
                    }

                    // --- 核心映射逻辑 ---
                    if (idA.equals(cellId)) {
                        data.setVaRaw(vRaw);
                        data.setCaRaw(cRaw);
                        data.setVa(v);
                        data.setCa(c);
                    } else if (idB.equals(cellId)) {
                        data.setVbRaw(vRaw);
                        data.setCbRaw(cRaw);
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
        // 优先按台账中“最近记录时间”选择电池，保证大屏展示的是最近一组
        String[] latestByLedger = pickLatestTwoCellIdsFromLedger();
        if (latestByLedger != null && latestByLedger[0] != null) {
            String idA = latestByLedger[0];
            String idB = latestByLedger[1] != null ? latestByLedger[1] : latestByLedger[0];
            return getDashboardStream(idA, idB);
        }

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
                    // 原始电压和电流
                    final double vRaw = v;
                    final double cRaw = c;
                    // 平滑电压和电流
                    if (cellId != null) {
                        String ck = cellId.toLowerCase();
                        v = signalFilterService.smoothVoltageReading(ck, v);
                        c = signalFilterService.smoothCurrentReading(ck, c);
                    }

                    if (record.getTime() != null) {
                        if (latestTime == null || record.getTime().isAfter(latestTime)) {
                            latestTime = record.getTime();
                        }
                    }

                    if (idA.equals(cellId)) {
                        data.setVaRaw(vRaw);
                        data.setCaRaw(cRaw);
                        data.setVa(v);
                        data.setCa(c);
                    } else if (idB.equals(cellId)) {
                        data.setVbRaw(vRaw);
                        data.setCbRaw(cRaw);
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

    private String[] pickLatestTwoCellIdsFromLedger() {
        try {
            List<Battery> list = batteryRepository.findTop2ByDeletedFalseAndLastRecordAtIsNotNullOrderByLastRecordAtDescIdDesc();
            if (list == null || list.isEmpty()) return null;

            String idA = list.get(0).getBatteryCode();
            if (idA == null || idA.trim().isEmpty()) return null;
            idA = idA.trim();

            String idB = null;
            if (list.size() > 1 && list.get(1).getBatteryCode() != null) {
                idB = list.get(1).getBatteryCode().trim();
            }
            return new String[]{idA, idB};
        } catch (Exception e) {
            log.warn("⚠️ 按台账选择最近双通道电池失败，回退 Influx 逻辑: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取所有 cell_id 的最新电压/温度（与 {@link #getLatestVtcByCellId()} 共用缓存与一次 Influx 查询）。
     */
    public Map<String, LatestVt> getLatestVoltageTemperatureByCellId() {
        Map<String, LatestVtc> vtc = getLatestVtcByCellId();
        return latestVtcMapToVt(vtc);
    }

    /**
     * 仅查询给定 cell_id 集合的最新电压/温度（用于台账分页，避免全表 last()）。
     */
    public Map<String, LatestVt> getLatestVoltageTemperatureForCellIds(Set<String> cellIds) {
        if (cellIds == null || cellIds.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, LatestVtc> vtc = fetchLatestVtcMapFromInfluxForCellIds(cellIds);
        return latestVtcMapToVt(vtc);
    }

    private static Map<String, LatestVt> latestVtcMapToVt(Map<String, LatestVtc> vtc) {
        Map<String, LatestVt> map = new HashMap<>(Math.max(16, vtc.size() * 2));
        for (Map.Entry<String, LatestVtc> e : vtc.entrySet()) {
            LatestVtc z = e.getValue();
            LatestVt vt = new LatestVt();
            vt.setVoltage(z.getVoltage());
            vt.setTemperature(z.getTemperature());
            vt.setTime(z.getTime());
            map.put(e.getKey(), vt);
        }
        return map;
    }

    /**
     * 获取所有 cell_id 的最新电压/温度/电流（拓扑快照）；短时缓存避免与电池列表连续重复查 Influx。
     */
    public Map<String, LatestVtc> getLatestVtcByCellId() {
        long now = System.currentTimeMillis();
        if (now - latestVtcCacheAtMs < LATEST_VTC_CACHE_TTL_MS) {
            return new HashMap<>(latestVtcCache);
        }
        synchronized (latestVtcCacheLock) {
            if (System.currentTimeMillis() - latestVtcCacheAtMs < LATEST_VTC_CACHE_TTL_MS) {
                return new HashMap<>(latestVtcCache);
            }
            Map<String, LatestVtc> fresh = fetchLatestVtcMapFromInflux();
            latestVtcCache = fresh;
            latestVtcCacheAtMs = System.currentTimeMillis();
            return new HashMap<>(fresh);
        }
    }

    private Map<String, LatestVtc> fetchLatestVtcMapFromInflux() {
        return fetchLatestVtcMapFromInfluxWithOptionalCellFilter(null);
    }

    /**
     * 仅拉取指定 cell_id 的最新点；集合过大时退回全量查询后在内存按 key 过滤（仍走缓存）。
     */
    private static final int MAX_CELL_IDS_FOR_DIRECT_INFLUX_FILTER = 200;

    private Map<String, LatestVtc> fetchLatestVtcMapFromInfluxForCellIds(Set<String> cellIds) {
        if (cellIds == null || cellIds.isEmpty()) {
            return new HashMap<>();
        }
        if (cellIds.size() > MAX_CELL_IDS_FOR_DIRECT_INFLUX_FILTER) {
            Map<String, LatestVtc> full = getLatestVtcByCellId();
            Map<String, LatestVtc> sub = new HashMap<>();
            for (String id : cellIds) {
                if (id == null) {
                    continue;
                }
                LatestVtc v = full.get(id.toLowerCase());
                if (v != null) {
                    sub.put(id.toLowerCase(), v);
                }
            }
            return sub;
        }
        return fetchLatestVtcMapFromInfluxWithOptionalCellFilter(cellIds);
    }

    /**
     * @param cellIdFilter null 表示不过滤 cell（全量）；非空则 Influx 侧按正则限定 cell_id，显著减轻列表场景负载。
     */
    private Map<String, LatestVtc> fetchLatestVtcMapFromInfluxWithOptionalCellFilter(Set<String> cellIdFilter) {
        Map<String, LatestVtc> map = new HashMap<>();
        String cellIdClause = "";
        if (cellIdFilter != null && !cellIdFilter.isEmpty()) {
            String inner = cellIdFilter.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(BatteryDataService::escapeRe2Literal)
                    .distinct()
                    .collect(Collectors.joining("|"));
            if (!inner.isEmpty()) {
                // (?i) 与台账/Influx tag 大小写混用兼容；inner 为 RE2 字面量（非 Java \Q...\E，Influx 不支持）
                cellIdClause = String.format(
                        "|> filter(fn: (r) => r[\"cell_id\"] =~ /^(?i)(?:%s)$/) ",
                        inner
                );
            }
        }

        String query = String.format(
                "from(bucket: \"%s\") " +
                        "|> range(start: %s) " +
                        "|> filter(fn: (r) => r[\"_measurement\"] == \"battery_metrics\") " +
                        "|> filter(fn: (r) => r[\"_field\"] == \"voltage\" or r[\"_field\"] == \"temperature\" or r[\"_field\"] == \"current\") " +
                        "%s" +
                        "|> group(columns: [\"cell_id\", \"_field\"]) " +
                        "|> last() " +
                        "|> keep(columns: [\"cell_id\", \"_field\", \"_value\", \"_time\"])",
                bucket,
                influxLatestRange,
                cellIdClause
        );

        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(query, org);
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    String cellId = (String) record.getValueByKey("cell_id");
                    if (cellId == null) continue;
                    String field = String.valueOf(record.getValueByKey("_field"));
                    LatestVtc vtc = map.computeIfAbsent(cellId.toLowerCase(), k -> new LatestVtc());
                    if ("voltage".equals(field)) {
                        vtc.setVoltage(getDoubleValue(record.getValue()));
                    } else if ("temperature".equals(field)) {
                        vtc.setTemperature(getDoubleValue(record.getValue()));
                    } else if ("current".equals(field)) {
                        vtc.setCurrent(getDoubleValue(record.getValue()));
                    }
                    if (record.getTime() != null) {
                        if (vtc.getTime() == null || record.getTime().isAfter(vtc.getTime())) {
                            vtc.setTime(record.getTime());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ 查询 InfluxDB 最新 voltage/temperature/current 失败: {}", e.getMessage());
        }
        return map;
    }

    /**
     * Influx Flux 正则使用 RE2，不支持 Java {@code Pattern.quote} 的 {@code \Q...\E}。
     * 将字符串按字面量匹配时，对 RE2 元字符做转义。
     */
    private static String escapeRe2Literal(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                case '.':
                case '+':
                case '*':
                case '?':
                case '(':
                case ')':
                case '|':
                case '[':
                case ']':
                case '{':
                case '}':
                case '^':
                case '$':
                    sb.append('\\');
                    break;
                default:
                    break;
            }
            sb.append(c);
        }
        return sb.toString();
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

    public static class LatestVtc {
        private double voltage;
        private double temperature;
        private double current;
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

        public double getCurrent() {
            return current;
        }

        public void setCurrent(double current) {
            this.current = current;
        }

        public Instant getTime() {
            return time;
        }

        public void setTime(Instant time) {
            this.time = time;
        }
    }

    public IcAnalysisResponse getIcAnalysis(String cellId, Integer refCycle, Integer currCycle, int smoothWindow) {
        IcAnalysisResponse resp = new IcAnalysisResponse();
        resp.setCellId(cellId);
        if (cellId == null || cellId.trim().isEmpty()) {
            return resp;
        }

        List<Integer> cycles = queryAvailableCycles(cellId);
        if (cycles.isEmpty()) {
            return resp;
        }
        int defaultRef = cycles.get(0);
        int defaultCurr = cycles.get(cycles.size() - 1);
        int finalRef = refCycle != null ? refCycle : defaultRef;
        int finalCurr = currCycle != null ? currCycle : defaultCurr;
        resp.setRefCycle(finalRef);
        resp.setCurrCycle(finalCurr);

        List<IcPointDto> ref = calculateIcCurve(cellId, finalRef, smoothWindow);
        List<IcPointDto> curr = calculateIcCurve(cellId, finalCurr, smoothWindow);
        resp.setRefCurve(ref);
        resp.setCurrCurve(curr);

        if (!ref.isEmpty() && !curr.isEmpty()) {
            IcPointDto refPeak = ref.stream().max(Comparator.comparing(IcPointDto::getDqdv)).orElse(null);
            IcPointDto currPeak = curr.stream().max(Comparator.comparing(IcPointDto::getDqdv)).orElse(null);
            if (refPeak != null && currPeak != null) {
                resp.setPeakShift(currPeak.getVoltage() - refPeak.getVoltage());
                if (refPeak.getDqdv() != null && Math.abs(refPeak.getDqdv()) > 1e-9) {
                    resp.setPeakDropPercent((refPeak.getDqdv() - currPeak.getDqdv()) / refPeak.getDqdv() * 100.0);
                }
            }
        }
        return resp;
    }

    private List<Integer> queryAvailableCycles(String cellId) {
        Set<Integer> cycleSet = new HashSet<>();
        String query = String.format(
                "from(bucket: \"%s\") " +
                        "|> range(start: -365d) " +
                        "|> filter(fn: (r) => r[\"_measurement\"] == \"battery_metrics\") " +
                        "|> filter(fn: (r) => r[\"cell_id\"] == \"%s\") " +
                        "|> filter(fn: (r) => r[\"_field\"] == \"capacity\") " +
                        "|> group(columns: [\"cycle_index\"]) " +
                        "|> last() " +
                        "|> keep(columns: [\"cycle_index\"])",
                bucket, cellId
        );
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(query, org);
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Object raw = record.getValueByKey("cycle_index");
                    if (raw == null) continue;
                    try {
                        cycleSet.add(Integer.parseInt(String.valueOf(raw)));
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ 查询 cycle_index 失败: {}", e.getMessage());
        }
        return cycleSet.stream().sorted().collect(Collectors.toList());
    }

    private List<IcPointDto> calculateIcCurve(String cellId, int cycle, int smoothWindow) {
        List<double[]> points = queryVoltageCapacityPoints(cellId, cycle);
        if (points.size() < 3) return new ArrayList<>();

        List<IcPointDto> raw = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            double v1 = points.get(i - 1)[0];
            double q1 = points.get(i - 1)[1];
            double v2 = points.get(i)[0];
            double q2 = points.get(i)[1];
            double vmid = (v1 + v2) / 2.0;
            if (vmid < IC_V_MIN || vmid > IC_V_MAX) continue;
            double dv = v2 - v1;
            if (Math.abs(dv) < 1e-6) continue;
            double dqdv = (q2 - q1) / dv;
            if (Double.isNaN(dqdv) || Double.isInfinite(dqdv)) continue;
            dqdv = Math.max(-DQDV_ABS_CLIP, Math.min(DQDV_ABS_CLIP, dqdv));
            raw.add(new IcPointDto(vmid, dqdv));
        }
        List<IcPointDto> robust = robustClip(raw);
        List<IcPointDto> smoothed = smoothIc(robust, Math.max(1, smoothWindow));
        smoothed.sort(Comparator.comparingDouble(p -> p.getVoltage() == null ? 0.0 : p.getVoltage()));
        return smoothed;
    }

    private List<double[]> queryVoltageCapacityPoints(String cellId, int cycle) {
        class Row {
            Instant t;
            Double v;
            Double c;
            Double qCap;
        }
        List<Row> rows = new ArrayList<>();
        String query = String.format(
                "from(bucket: \"%s\") " +
                        "|> range(start: -365d) " +
                        "|> filter(fn: (r) => r[\"_measurement\"] == \"battery_metrics\") " +
                        "|> filter(fn: (r) => r[\"cell_id\"] == \"%s\") " +
                        "|> filter(fn: (r) => r[\"cycle_index\"] == \"%d\") " +
                        "|> filter(fn: (r) => r[\"_field\"] == \"voltage\" or r[\"_field\"] == \"capacity\" or r[\"_field\"] == \"current\") " +
                        "|> pivot(rowKey:[\"_time\"], columnKey:[\"_field\"], valueColumn:\"_value\") " +
                        "|> keep(columns:[\"_time\", \"voltage\", \"capacity\", \"current\"]) " +
                        "|> sort(columns:[\"_time\"])",
                bucket, cellId, cycle
        );
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(query, org);
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Object vRaw = record.getValueByKey("voltage");
                    Object cRaw = record.getValueByKey("current");
                    Object qRaw = record.getValueByKey("capacity");
                    if (vRaw == null || cRaw == null || record.getTime() == null) continue;
                    Row row = new Row();
                    row.t = record.getTime();
                    row.v = getDoubleValue(vRaw);
                    row.c = getDoubleValue(cRaw);
                    row.qCap = qRaw == null ? null : getDoubleValue(qRaw);
                    if (row.v == null || row.v <= 0 || Double.isNaN(row.v) || Double.isInfinite(row.v)) continue;
                    rows.add(row);
                }
            }
        } catch (Exception e) {
            log.error("❌ 查询 IC 原始点失败 cellId={}, cycle={}, err={}", cellId, cycle, e.getMessage());
        }
        rows.sort(Comparator.comparing(r -> r.t));
        if (rows.size() < 2) return new ArrayList<>();

        // 如果 capacity 在该 cycle 内有变化，则优先用 capacity；否则退化为电流积分得到 Q(t)
        boolean hasCapTrend = false;
        Double firstCap = null;
        for (Row r : rows) {
            if (r.qCap == null || Double.isNaN(r.qCap) || Double.isInfinite(r.qCap)) continue;
            if (firstCap == null) firstCap = r.qCap;
            if (firstCap != null && Math.abs(r.qCap - firstCap) > 1e-6) {
                hasCapTrend = true;
                break;
            }
        }

        List<double[]> list = new ArrayList<>();
        if (hasCapTrend) {
            for (Row r : rows) {
                if (r.qCap == null || Double.isNaN(r.qCap) || Double.isInfinite(r.qCap)) continue;
                list.add(new double[]{r.v, r.qCap});
            }
            return list;
        }

        double qAh = 0.0;
        list.add(new double[]{rows.get(0).v, qAh});
        for (int i = 1; i < rows.size(); i++) {
            Row prev = rows.get(i - 1);
            Row curr = rows.get(i);
            long dtMs = curr.t.toEpochMilli() - prev.t.toEpochMilli();
            if (dtMs <= 0) continue;
            double dtHour = dtMs / 3600000.0;
            double iAvg = (Math.abs(prev.c) + Math.abs(curr.c)) / 2.0;
            qAh += iAvg * dtHour;
            list.add(new double[]{curr.v, qAh});
        }
        return list;
    }

    private List<IcPointDto> smoothIc(List<IcPointDto> src, int window) {
        if (window <= 1 || src.size() <= 2) return src;
        List<IcPointDto> out = new ArrayList<>(src.size());
        int half = window / 2;
        for (int i = 0; i < src.size(); i++) {
            int l = Math.max(0, i - half);
            int r = Math.min(src.size() - 1, i + half);
            double sum = 0.0;
            int n = 0;
            for (int j = l; j <= r; j++) {
                if (src.get(j).getDqdv() != null) {
                    sum += src.get(j).getDqdv();
                    n++;
                }
            }
            double avg = n > 0 ? sum / n : 0.0;
            out.add(new IcPointDto(src.get(i).getVoltage(), avg));
        }
        return out;
    }

    /**
     * 使用 MAD（中位数绝对偏差）做鲁棒裁剪，抑制尖峰。
     */
    private List<IcPointDto> robustClip(List<IcPointDto> src) {
        if (src == null || src.size() < 8) return src;
        List<Double> vals = src.stream()
                .map(IcPointDto::getDqdv)
                .filter(v -> v != null && !Double.isNaN(v) && !Double.isInfinite(v))
                .sorted()
                .collect(Collectors.toList());
        if (vals.size() < 8) return src;

        double median = vals.get(vals.size() / 2);
        List<Double> absDev = vals.stream()
                .map(v -> Math.abs(v - median))
                .sorted()
                .collect(Collectors.toList());
        double mad = absDev.get(absDev.size() / 2);
        if (mad < 1e-9) return src;

        double sigma = 1.4826 * mad;
        double lo = median - 3.5 * sigma;
        double hi = median + 3.5 * sigma;

        List<IcPointDto> out = new ArrayList<>(src.size());
        for (IcPointDto p : src) {
            if (p.getDqdv() == null) {
                out.add(p);
                continue;
            }
            double v = p.getDqdv();
            if (v < lo) v = lo;
            if (v > hi) v = hi;
            out.add(new IcPointDto(p.getVoltage(), v));
        }
        return out;
    }
}