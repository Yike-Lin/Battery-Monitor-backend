package com.bms.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync			// 开启异步
@EnableScheduling      // 开启定时任务（拓扑快照聚合）
public class BatteryMonitorBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BatteryMonitorBackendApplication.class, args);
	}

}
