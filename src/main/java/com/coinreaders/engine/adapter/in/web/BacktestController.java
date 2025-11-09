package com.coinreaders.engine.adapter.in.web;

import com.coinreaders.engine.application.backtest.BacktestService;
import com.coinreaders.engine.application.backtest.dto.BacktestRequest;
import com.coinreaders.engine.application.backtest.dto.BacktestResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
@Slf4j
public class BacktestController {

    private final BacktestService backtestService;

    /**
     * 백테스팅 실행 API
     *
     * @param foldNumber Fold 번호 (1~8)
     * @param initialCapital 초기 자본 (기본값: 10000)
     * @param confidenceThreshold 신뢰도 임계값 (기본값: 0.5)
     * @return Kelly vs Buy & Hold 비교 결과
     */
    @PostMapping("/run")
    public ResponseEntity<BacktestResponse> runBacktest(
        @RequestParam Integer foldNumber,
        @RequestParam(required = false, defaultValue = "10000") BigDecimal initialCapital,
        @RequestParam(required = false, defaultValue = "0.5") BigDecimal confidenceThreshold
    ) {
        log.info("백테스팅 API 호출: foldNumber={}, initialCapital={}, confidenceThreshold={}",
            foldNumber, initialCapital, confidenceThreshold);

        BacktestRequest request = new BacktestRequest(foldNumber, initialCapital, confidenceThreshold);
        BacktestResponse response = backtestService.runBacktest(request);

        return ResponseEntity.ok(response);
    }

    /**
     * 백테스팅 실행 API (Request Body 방식)
     */
    @PostMapping("/run-body")
    public ResponseEntity<BacktestResponse> runBacktestWithBody(@RequestBody BacktestRequest request) {
        log.info("백테스팅 API 호출 (Body): {}", request);

        BacktestResponse response = backtestService.runBacktest(request);

        return ResponseEntity.ok(response);
    }

    /**
     * Fold 정보 조회 API
     */
    @GetMapping("/fold/{foldNumber}")
    public ResponseEntity<FoldInfo> getFoldInfo(@PathVariable Integer foldNumber) {
        var foldConfig = com.coinreaders.engine.application.backtest.FoldConfig.getFold(foldNumber);

        FoldInfo info = new FoldInfo(
            foldConfig.getFoldNumber(),
            foldConfig.getStartDate().toString(),
            foldConfig.getEndDate().toString(),
            foldConfig.getRegime()
        );

        return ResponseEntity.ok(info);
    }

    // Fold 정보 DTO
    record FoldInfo(
        int foldNumber,
        String startDate,
        String endDate,
        String regime
    ) {}
}
