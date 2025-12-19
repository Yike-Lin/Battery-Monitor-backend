package com.bms.backend.controller;

import com.bms.backend.service.DataSimulationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    @Autowired
    private DataSimulationService simulationService;

    // 启动双通道模拟
    // GET http://localhost:8080/api/simulation/start
    @GetMapping("/start")
    public String start() {
        String projectRoot = System.getProperty("user.dir");

        // --- 准备通道 A (b1c0) ---
        String pathA = Paths.get(projectRoot, "data", "XJTU", "charge", "batch1_b1c0.csv").toString();

        // --- 准备通道 B (b1c1) ---
        //
        String pathB = Paths.get(projectRoot, "data", "XJTU", "charge", "batch1_b1c1.csv").toString();

        // 简单检查文件是否存在
        if (!new File(pathA).exists() || !new File(pathB).exists()) {
            return "❌ 启动失败：找不到文件！请检查 data/XJTU/charge/ 目录下是否有 b1c0 和 b1c1 文件。";
        }

        // --- 并发启动 ---
        simulationService.startSimulation(pathA, "b1c0");
        simulationService.startSimulation(pathB, "b1c1");

        return "🚀 双通道模拟已启动！\n" +
                "🔋 通道A: 读取 batch1_b1c0.csv -> 写入 ID: b1c0\n" +
                "🔋 通道B: 读取 batch1_b1c1.csv -> 写入 ID: b1c1";
    }

    @GetMapping("/stop")
    public String stop() {
        simulationService.stopSimulation();
        return "🛑 已发送停止指令，所有通道将停止。";
    }
}