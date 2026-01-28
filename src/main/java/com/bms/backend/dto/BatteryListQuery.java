package com.bms.backend.dto;


import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 列表查询条件DTO
 */
@Data
public class BatteryListQuery {
    // 查询表单中的字段
    private String batteryCode;
    private String modelCode;
    private Short status;

    // 投运日期开始
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate commissioningDateStart;

    // 投运日期结束
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate commissioningDateEnd;

    // 分页参数
    private Integer page = 0;
    private Integer size = 10;
}
