package com.bms.backend;

import com.bms.backend.service.SohPredictService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class SohPredictServiceTest {

    @Autowired
    private SohPredictService sohPredictService;

    @Test
    public void testPredictFromCsv() {
        String fileKey = "bms/csv/2026/02/07/b9edce26fc774a378dee0d79043b29f5.csv";
        int cycleForPredict = 1;

        Double soh = sohPredictService.predictSohFromCsvFileKey(fileKey, cycleForPredict);

        System.out.println("SOH predict = " + soh);

        Assertions.assertNotNull(soh);
        Assertions.assertTrue(soh > 0 && soh < 1);
    }
}
