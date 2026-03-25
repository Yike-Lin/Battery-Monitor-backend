package com.bms.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时量测在线平滑：按 cell_id（小写）分片状态，供多线程读场景下合并一次 Influx 查询后复用。
 * 实现为 EMA + 可选阶跃限幅；长时间无采样则冷启动，避免旧状态拖尾。
 */
@Service
public class RealtimeSignalFilterService {

    @Value("${bms.signal-filter.enabled:true}")
    private boolean enabled;

    @Value("${bms.signal-filter.voltage-alpha:0.35}")
    private double voltageAlpha;

    @Value("${bms.signal-filter.temperature-alpha:0.30}")
    private double temperatureAlpha;

    @Value("${bms.signal-filter.current-alpha:0.40}")
    private double currentAlpha;

    /** 单次刷新相对上一笔原始采样允许的最大跳变；0 表示不限幅 */
    @Value("${bms.signal-filter.max-delta-voltage:0.06}")
    private double maxDeltaVoltage;

    @Value("${bms.signal-filter.max-delta-temperature:2.0}")
    private double maxDeltaTemperature;

    @Value("${bms.signal-filter.max-delta-current:0.2}")
    private double maxDeltaCurrent;

    @Value("${bms.signal-filter.stale-reset-seconds:120}")
    private long staleResetSeconds;

    private final ConcurrentHashMap<String, ChannelState> channels = new ConcurrentHashMap<>();

    private static final class ChannelState {
        Double ema;
        Double lastRaw;
        long lastSampleEpochMs;
    }

    public Map<String, BatteryDataService.LatestVt> smoothVtMap(Map<String, BatteryDataService.LatestVt> src) {
        if (!enabled || src == null || src.isEmpty()) {
            return src;
        }
        Map<String, BatteryDataService.LatestVt> out = new HashMap<>(src.size());
        for (Map.Entry<String, BatteryDataService.LatestVt> e : src.entrySet()) {
            out.put(e.getKey(), smoothVt(e.getKey(), e.getValue()));
        }
        return out;
    }

    public Map<String, BatteryDataService.LatestVtc> smoothVtcMap(Map<String, BatteryDataService.LatestVtc> src) {
        if (!enabled || src == null || src.isEmpty()) {
            return src;
        }
        Map<String, BatteryDataService.LatestVtc> out = new HashMap<>(src.size());
        for (Map.Entry<String, BatteryDataService.LatestVtc> e : src.entrySet()) {
            out.put(e.getKey(), smoothVtc(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** 双通道等大屏：对单次读到的电压/电流做平滑（key 可用 cellId 小写）。 */
    public double smoothVoltageReading(String cellKeyLower, double rawVolts) {
        if (!enabled) return rawVolts;
        return updateChannel(channelKey(cellKeyLower, "v"), voltageAlpha, maxDeltaVoltage, rawVolts, Instant.now());
    }

    public double smoothCurrentReading(String cellKeyLower, double rawAmps) {
        if (!enabled) return rawAmps;
        return updateChannel(channelKey(cellKeyLower, "i"), currentAlpha, maxDeltaCurrent, rawAmps, Instant.now());
    }

    private BatteryDataService.LatestVt smoothVt(String cellKeyLower, BatteryDataService.LatestVt raw) {
        if (raw == null) {
            return null;
        }
        BatteryDataService.LatestVt out = new BatteryDataService.LatestVt();
        Instant t = raw.getTime();
        long ms = t != null ? t.toEpochMilli() : System.currentTimeMillis();
        out.setTime(t);
        out.setVoltage(updateChannel(channelKey(cellKeyLower, "v"), voltageAlpha, maxDeltaVoltage, raw.getVoltage(), ms));
        out.setTemperature(updateChannel(channelKey(cellKeyLower, "t"), temperatureAlpha, maxDeltaTemperature, raw.getTemperature(), ms));
        return out;
    }

    private BatteryDataService.LatestVtc smoothVtc(String cellKeyLower, BatteryDataService.LatestVtc raw) {
        if (raw == null) {
            return null;
        }
        BatteryDataService.LatestVtc out = new BatteryDataService.LatestVtc();
        Instant t = raw.getTime();
        long ms = t != null ? t.toEpochMilli() : System.currentTimeMillis();
        out.setTime(t);
        out.setVoltage(updateChannel(channelKey(cellKeyLower, "v"), voltageAlpha, maxDeltaVoltage, raw.getVoltage(), ms));
        out.setTemperature(updateChannel(channelKey(cellKeyLower, "t"), temperatureAlpha, maxDeltaTemperature, raw.getTemperature(), ms));
        out.setCurrent(updateChannel(channelKey(cellKeyLower, "i"), currentAlpha, maxDeltaCurrent, raw.getCurrent(), ms));
        return out;
    }

    private static String channelKey(String cellKeyLower, String suffix) {
        return cellKeyLower + '\0' + suffix;
    }

    private double updateChannel(String key, double alpha, double maxDelta, double raw, Instant sampleTime) {
        long ms = sampleTime != null ? sampleTime.toEpochMilli() : System.currentTimeMillis();
        return updateChannel(key, alpha, maxDelta, raw, ms);
    }

    private double updateChannel(String key, double alpha, double maxDelta, double raw, long sampleEpochMs) {
        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
            ChannelState st = channels.get(key);
            return st != null && st.ema != null ? st.ema : raw;
        }
        final long staleMs = staleResetSeconds * 1000L;
        channels.compute(key, (k, st) -> {
            ChannelState s = st != null ? st : new ChannelState();
            if (st != null && staleMs > 0 && sampleEpochMs - s.lastSampleEpochMs > staleMs) {
                s.ema = null;
                s.lastRaw = null;
            }
            double x = raw;
            if (maxDelta > 0 && s.lastRaw != null) {
                double d = x - s.lastRaw;
                if (d > maxDelta) x = s.lastRaw + maxDelta;
                else if (d < -maxDelta) x = s.lastRaw - maxDelta;
            }
            if (s.ema == null) {
                s.ema = x;
            } else {
                s.ema = alpha * x + (1.0 - alpha) * s.ema;
            }
            s.lastRaw = x;
            s.lastSampleEpochMs = sampleEpochMs;
            return s;
        });
        ChannelState out = channels.get(key);
        return out != null && out.ema != null ? out.ema : raw;
    }
}
