package com.coinreaders.engine.adapter.in.web;

import com.coinreaders.engine.application.DataPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/data")
@RequiredArgsConstructor
public class DataPipelineController {

    private final DataPipelineService dataPipelineService;

    /**
     * (UC-009) Upbit API로 최신 일봉 200개를 적재합니다. (실거래/최신화용)
     */
    @PostMapping("/init-ohlcv-recent")
    public ResponseEntity<String> initializeOhlcvData() {
        dataPipelineService.initializeDayCandles("KRW-ETH", 200);
        return ResponseEntity.ok("Recent 200 candles initialization request accepted.");
    }

    /**
     * (UC-001) Upbit API로 2017-11-09까지의 모든 일봉을 적재합니다. (백테스팅 최초 구축용)
     */
    @PostMapping("/init-ohlcv-all")
    public ResponseEntity<String> initializeAllHistoricalOhlcv() {
        try {
            dataPipelineService.loadAllHistoricalOhlcv("KRW-ETH", "2017-11-09");
            return ResponseEntity.ok("All historical data initialization completed successfully.");
        } catch (Exception e) {
            log.error("Failed to load all historical data", e);
            return ResponseEntity.internalServerError().body("Failed to load data: " + e.getMessage());
        }
    }
}