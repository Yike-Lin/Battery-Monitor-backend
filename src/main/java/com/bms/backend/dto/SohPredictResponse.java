package com.bms.backend.dto;

import lombok.Data;

/**
 * SohPredict返回映射体
 */
@Data
public class SohPredictResponse {
    private int code;
    private String msg;
    private SohData data;

    @Data
    public static class SohData {
        private Double soh;
        private String health_status;
    }

}
