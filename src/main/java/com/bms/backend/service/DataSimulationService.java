package com.bms.backend.service;

import com.bms.backend.entity.BatteryData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Random;

@Service
@EnableScheduling
public class DataSimulationService {

    // 手动创建日志对象
    private static final Logger log = LoggerFactory.getLogger(DataSimulationService.class);

    @Autowired
    private InfluxDbService influxDbService;

    private final Random random = new Random();
    private double timeStep = 0;

    @Scheduled(fixedRate = 1000)
    public void generateFakeData() {
        BatteryData data = new BatteryData();

        data.setBatteryId("BAT-001");
        data.setTime(Instant.now());

        double voltageNoise = (random.nextDouble() - 0.5) * 0.2;
        double voltageBase = 48.0 + Math.sin(timeStep) * 1.5;
        data.setVoltage(voltageBase + voltageNoise);

        data.setCurrent(5.0 + (random.nextDouble() - 0.5));

        double tempBase = 25.0 + (timeStep * 0.05) % 30;
        data.setTemperature(tempBase + random.nextDouble() * 0.5);

        data.setSoc(80.0 - (timeStep * 0.01) % 80);

        timeStep += 0.1;

        influxDbService.writeOne(data);

        // 这里的日志也用 log 变量
        // log.info("生成数据..."); // 如果你想看日志可以把这行注释打开
    }
}