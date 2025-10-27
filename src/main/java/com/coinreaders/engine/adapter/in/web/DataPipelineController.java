package com.coinreaders.engine.adapter.in.web;

import com.coinreaders.engine.application.DataPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/data")
@RequiredArgsConstructor
public class DataPipelineController {

    private final DataPipelineService dataPipelineService;

    /**
     * 과거 일봉 데이터를 초기 적재합니다.
     */
    @PostMapping("/init-ohlcv")
    public ResponseEntity<String> initializeOhlcvData() {
        // KRW-ETH 마켓의 최근 200일치 데이터를 적재
        dataPipelineService.initializeDayCandles("KRW-ETH", 200);

        // 실제로는 비동기로 처리되므로 "요청 성공" 응답을 즉시 반환
        return ResponseEntity.ok("Data initialization request accepted.");
    }
}