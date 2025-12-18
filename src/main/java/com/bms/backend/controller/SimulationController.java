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

    // 启动模拟
    // POST http://localhost:8080/api/simulation/start
    @GetMapping("/start")
    public String start() {
        // 1. 获取当前项目的根目录
        String projectRoot = System.getProperty("user.dir");

        // 2. 拼接特定的子目录路径: data/XJTU/charge/batch1_b1c0.csv
        // Paths.get 自动处理 Windows的反斜杠(\)
        String csvPath = Paths.get(projectRoot, "data", "XJTU", "charge", "batch1_b1c0.csv").toString();

        // 3. 检查文件是否存在（防止报错）
        File file = new File(csvPath);
        if (!file.exists()) {
            System.err.println("文件未找到: " + csvPath);
            return "❌ 启动失败：找不到文件！\n请确认文件位于: " + csvPath;
        }

        // 4. 启动服务
        simulationService.startSimulation(csvPath);
        return "🚀 模拟器已启动！\n正在读取文件: " + csvPath;
    }

    // 停止模拟
    // POST http://localhost:8080/api/simulation/stop
    @GetMapping("/stop")
    public String stop() {
        simulationService.stopSimulation();
        return "🛑 模拟器停止指令已发送。";
    }
}