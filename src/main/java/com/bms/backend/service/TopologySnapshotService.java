package com.bms.backend.service;

import com.bms.backend.dto.TopologyCellDto;
import com.bms.backend.dto.TopologySnapshotDto;
import com.bms.backend.entity.Battery;
import com.bms.backend.repository.BatteryRepository;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class TopologySnapshotService {

    private final BatteryRepository batteryRepository;
    private final BatteryDataService batteryDataService;
    private final AtomicReference<Map<String, TopologySnapshotDto>> latestByPack =
            new AtomicReference<>(Collections.emptyMap());

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Map<SseEmitter, String> emitterPackFilter = new ConcurrentHashMap<>();

    private static final Pattern CELL_INDEX_PATTERN = Pattern.compile("c(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final int GRID_COLS = 8;

    public TopologySnapshotService(BatteryRepository batteryRepository,
                                   BatteryDataService batteryDataService) {
        this.batteryRepository = batteryRepository;
        this.batteryDataService = batteryDataService;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1500)
    public void refreshSnapshot() {
        boolean stale = false;
        try {
            Map<String, BatteryDataService.LatestVtc> vtcByCell = batteryDataService.getLatestVtcByCellId();
            List<Battery> batteries = batteryRepository.findByDeletedFalse();
            Map<String, List<Battery>> byPack = batteries.stream()
                    .filter(b -> b.getBatteryCode() != null && !b.getBatteryCode().trim().isEmpty())
                    .collect(Collectors.groupingBy(b -> detectPackId(b.getBatteryCode())));

            Map<String, TopologySnapshotDto> next = new ConcurrentHashMap<>();
            long now = Instant.now().toEpochMilli();
            for (Map.Entry<String, List<Battery>> e : byPack.entrySet()) {
                String packId = e.getKey();
                TopologySnapshotDto snap = new TopologySnapshotDto();
                snap.setPackId(packId);
                snap.setTs(now);
                snap.setStale(false);
                snap.setCells(buildCells(packId, e.getValue(), vtcByCell));
                computeSummary(snap);
                next.put(packId, snap);
            }
            latestByPack.set(next);
        } catch (Exception ignored) {
            stale = true;
        }
        broadcast(stale);
    }

    public TopologySnapshotDto getSnapshot(String packId) {
        Map<String, TopologySnapshotDto> all = latestByPack.get();
        if (all.isEmpty()) return emptySnapshot(packId, true);
        if (packId != null && !packId.trim().isEmpty()) {
            TopologySnapshotDto one = all.get(packId);
            return one != null ? one : emptySnapshot(packId, true);
        }
        return mergeAllPacks(all);
    }

    public SseEmitter subscribe(String packId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        String normalizedPack = (packId == null || packId.trim().isEmpty()) ? "ALL" : packId.trim();
        emitterPackFilter.put(emitter, normalizedPack);
        emitter.onCompletion(() -> cleanupEmitter(emitter));
        emitter.onTimeout(() -> cleanupEmitter(emitter));
        emitter.onError((ex) -> cleanupEmitter(emitter));
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(getSnapshot("ALL".equals(normalizedPack) ? null : normalizedPack)));
        } catch (Exception ignored) {
            cleanupEmitter(emitter);
        }
        return emitter;
    }

    private List<TopologyCellDto> buildCells(String packId,
                                             List<Battery> batteries,
                                             Map<String, BatteryDataService.LatestVtc> vtcByCell) {
        List<TopologyCellDto> cells = new ArrayList<>();
        for (Battery b : batteries) {
            String code = b.getBatteryCode();
            String key = code == null ? "" : code.toLowerCase();
            BatteryDataService.LatestVtc vtc = vtcByCell.get(key);
            TopologyCellDto c = new TopologyCellDto();
            c.setPackId(packId);
            c.setCellId(code);
            int idx = extractCellIndex(code);
            c.setRow(idx >= 0 ? idx / GRID_COLS : 0);
            c.setCol(idx >= 0 ? idx % GRID_COLS : 0);
            c.setModuleIndex(idx >= 0 ? idx / 16 : 0);
            if (vtc != null) {
                c.setVoltage(vtc.getVoltage());
                c.setTemperature(vtc.getTemperature());
                c.setCurrent(vtc.getCurrent());
            }
            scoreAndStatus(c);
            cells.add(c);
        }
        cells.sort((a, b) -> {
            int r = Integer.compare(a.getRow(), b.getRow());
            return r != 0 ? r : Integer.compare(a.getCol(), b.getCol());
        });
        return cells;
    }

    private void scoreAndStatus(TopologyCellDto c) {
        int score = 100;
        List<String> reasons = c.getReasons();
        Double v = c.getVoltage();
        Double t = c.getTemperature();

        if (v != null) {
            if (v >= 4.2 || v <= 3.0) {
                score -= 40;
                reasons.add("voltage_out_of_range");
            } else if (v >= 4.15 || v <= 3.1) {
                score -= 20;
                reasons.add("voltage_near_limit");
            }
        } else {
            score -= 10;
            reasons.add("voltage_missing");
        }

        if (t != null) {
            if (t >= 45) {
                score -= 40;
                reasons.add("temperature_high");
            } else if (t >= 35) {
                score -= 20;
                reasons.add("temperature_warn");
            }
        } else {
            score -= 10;
            reasons.add("temperature_missing");
        }

        score = Math.max(0, Math.min(100, score));
        c.setScore(score);
        if (score < 50) c.setStatus("alarm");
        else if (score < 80) c.setStatus("warn");
        else c.setStatus("normal");
    }

    private void computeSummary(TopologySnapshotDto snapshot) {
        int normal = 0, warn = 0, alarm = 0;
        for (TopologyCellDto c : snapshot.getCells()) {
            if ("alarm".equals(c.getStatus())) alarm++;
            else if ("warn".equals(c.getStatus())) warn++;
            else normal++;
        }
        snapshot.getSummary().setTotal(snapshot.getCells().size());
        snapshot.getSummary().setNormal(normal);
        snapshot.getSummary().setWarn(warn);
        snapshot.getSummary().setAlarm(alarm);
    }

    private void broadcast(boolean staleFlag) {
        if (emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            String packId = emitterPackFilter.get(emitter);
            TopologySnapshotDto snap = getSnapshot("ALL".equals(packId) ? null : packId);
            if (staleFlag) snap.setStale(true);
            try {
                emitter.send(SseEmitter.event().name("snapshot").data(snap));
            } catch (Exception ignored) {
                cleanupEmitter(emitter);
            }
        }
    }

    private void cleanupEmitter(SseEmitter emitter) {
        emitters.remove(emitter);
        emitterPackFilter.remove(emitter);
    }

    private TopologySnapshotDto emptySnapshot(String packId, boolean stale) {
        TopologySnapshotDto dto = new TopologySnapshotDto();
        dto.setPackId(packId);
        dto.setTs(Instant.now().toEpochMilli());
        dto.setStale(stale);
        return dto;
    }

    private TopologySnapshotDto mergeAllPacks(Map<String, TopologySnapshotDto> all) {
        TopologySnapshotDto merged = new TopologySnapshotDto();
        merged.setPackId("ALL");
        merged.setTs(Instant.now().toEpochMilli());
        List<TopologyCellDto> cells = new ArrayList<>();
        for (TopologySnapshotDto snap : all.values()) {
            if (snap.getCells() != null) {
                cells.addAll(snap.getCells());
            }
        }
        cells.sort((a, b) -> {
            String pa = a.getPackId() == null ? "" : a.getPackId();
            String pb = b.getPackId() == null ? "" : b.getPackId();
            int p = pa.compareTo(pb);
            if (p != 0) return p;
            int r = Integer.compare(a.getRow() == null ? 0 : a.getRow(), b.getRow() == null ? 0 : b.getRow());
            if (r != 0) return r;
            return Integer.compare(a.getCol() == null ? 0 : a.getCol(), b.getCol() == null ? 0 : b.getCol());
        });
        merged.setCells(cells);
        computeSummary(merged);
        return merged;
    }

    private String detectPackId(String batteryCode) {
        if (batteryCode == null) return "default";
        int i = batteryCode.lastIndexOf('_');
        if (i > 0) return batteryCode.substring(0, i);
        return "default";
    }

    private int extractCellIndex(String batteryCode) {
        if (batteryCode == null) return -1;
        Matcher m = CELL_INDEX_PATTERN.matcher(batteryCode);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {
            return -1;
        }
    }
}

