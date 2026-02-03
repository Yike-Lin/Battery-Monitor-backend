package com.bms.backend.dto;

import lombok.Data;

/**
 * 电池记录查询条件
 */
@Data
public class BatteryRecordQuery {

    // 页码，从 0 开始
    private Integer page = 0;
    // 页大小
    private Integer size = 50;

    // 起始循环
    private Integer cycleStart;
    // 截止循环
    private Integer cycleEnd;

}
