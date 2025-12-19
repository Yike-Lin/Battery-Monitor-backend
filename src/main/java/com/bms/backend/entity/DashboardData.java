package com.bms.backend.entity;

import lombok.Data;

@Data
public class DashboardData {
    private String time;      // 时间字符串 "HH:mm:ss"
    private Double va = 0.0;  // Pack A 电压
    private Double ca = 0.0;  // Pack A 电流
    private Double vb = 0.0;  // Pack B 电压
    private Double cb = 0.0;  // Pack B 电流
}