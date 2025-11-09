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
     * @param positionSizePercent 포지션 크기 (0~100%), null이면 Kelly Criterion 자동 계산
     * @return Kelly vs Buy & Hold 비교 결과
     */
    @GetMapping("/run")
    public ResponseEntity<BacktestResponse> runBacktest(
        @RequestParam Integer foldNumber,
        @RequestParam(required = false, defaultValue = "10000") BigDecimal initialCapital,
        @RequestParam(required = false, defaultValue = "0.5") BigDecimal confidenceThreshold,
        @RequestParam(required = false) BigDecimal positionSizePercent
    ) {
        log.info("백테스팅 API 호출: foldNumber={}, initialCapital={}, confidenceThreshold={}, positionSizePercent={}",
            foldNumber, initialCapital, confidenceThreshold, positionSizePercent);

        BacktestRequest request = new BacktestRequest(foldNumber, initialCapital, confidenceThreshold, positionSizePercent);
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
     * Fold 1~7 연속 백테스팅 API
     * 각 fold의 최종 자본이 다음 fold의 초기 자본이 됨
     *
     * @param startFold 시작 Fold (기본값: 1)
     * @param endFold 종료 Fold (기본값: 7)
     * @param initialCapital 초기 자본 (기본값: 10000)
     * @param confidenceThreshold 신뢰도 임계값 (기본값: 0.5)
     * @param positionSizePercent 포지션 크기 (0~100%), null이면 Kelly Criterion 자동 계산
     * @return Fold별 결과 및 전체 요약
     */
    @GetMapping("/run-sequential")
    public ResponseEntity<com.coinreaders.engine.application.backtest.dto.SequentialBacktestResponse> runSequentialBacktest(
        @RequestParam(required = false, defaultValue = "1") Integer startFold,
        @RequestParam(required = false, defaultValue = "7") Integer endFold,
        @RequestParam(required = false, defaultValue = "10000") BigDecimal initialCapital,
        @RequestParam(required = false, defaultValue = "0.5") BigDecimal confidenceThreshold,
        @RequestParam(required = false) BigDecimal positionSizePercent
    ) {
        log.info("연속 백테스팅 API 호출: Fold {} ~ {}, initialCapital={}, confidenceThreshold={}, positionSizePercent={}",
            startFold, endFold, initialCapital, confidenceThreshold, positionSizePercent);

        var response = backtestService.runSequentialBacktest(
            startFold, endFold, initialCapital, confidenceThreshold, positionSizePercent
        );

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
