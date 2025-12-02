package com.coinreaders.engine.adapter.in.web;

import com.coinreaders.engine.application.backtest.BacktestService;
import com.coinreaders.engine.application.backtest.FoldConfig;
import com.coinreaders.engine.application.backtest.TakeProfitStopLossBacktestService;
import com.coinreaders.engine.application.backtest.dto.BacktestRequest;
import com.coinreaders.engine.application.backtest.dto.BacktestResponse;
import com.coinreaders.engine.application.backtest.dto.SequentialBacktestResponse;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestRequest;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestResponse;
import com.coinreaders.engine.application.backtest.dto.ThresholdMode;
import com.coinreaders.engine.application.backtest.dto.ConfidenceColumn;
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
    private final TakeProfitStopLossBacktestService tpSlBacktestService;

    /**
     * 백테스팅 실행 API
     *
     * @param foldNumber Fold 번호 (1~8)
     * @param initialCapital 초기 자본 (기본값: 10000)
     * @param confidenceThreshold 신뢰도 임계값 (기본값: 0.1)
     * @param confidenceColumn 사용할 컬럼 (CONFIDENCE 또는 PRED_PROBA_UP, 기본값: CONFIDENCE)
     * @param thresholdMode 임계값 모드 (FIXED 또는 QUANTILE, 기본값: FIXED)
     * @param positionSizePercent 포지션 크기 (0~100%), null이면 Kelly Criterion 자동 계산
     * @return Kelly vs Buy & Hold 비교 결과
     */
    @GetMapping("/run")
    public ResponseEntity<?> runBacktest(
        @RequestParam Integer foldNumber,
        @RequestParam(required = false, defaultValue = "10000") BigDecimal initialCapital,
        @RequestParam(required = false, defaultValue = "0.1") BigDecimal confidenceThreshold,
        @RequestParam(required = false, defaultValue = "CONFIDENCE") ConfidenceColumn confidenceColumn,
        @RequestParam(required = false, defaultValue = "FIXED") ThresholdMode thresholdMode,
        @RequestParam(required = false) BigDecimal positionSizePercent
    ) {
        log.info("백테스팅 API 호출: foldNumber={}, initialCapital={}, confidenceThreshold={}, confidenceColumn={}, thresholdMode={}, positionSizePercent={}",
            foldNumber, initialCapital, confidenceThreshold, confidenceColumn, thresholdMode, positionSizePercent);

        // 입력 검증
        if (foldNumber < 1 || foldNumber > 8) {
            return ResponseEntity.badRequest().body("foldNumber must be between 1 and 8");
        }
        if (initialCapital.compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body("initialCapital must be positive");
        }

        // 임계값 범위 검증 (컬럼과 모드에 따라 다름)
        BigDecimal maxFixed = confidenceColumn == ConfidenceColumn.CONFIDENCE ? new BigDecimal("0.5") : BigDecimal.ONE;
        if (thresholdMode == ThresholdMode.FIXED) {
            if (confidenceThreshold.compareTo(BigDecimal.ZERO) < 0 || confidenceThreshold.compareTo(maxFixed) > 0) {
                return ResponseEntity.badRequest().body("FIXED 모드: confidenceThreshold must be between 0 and " + maxFixed);
            }
        } else { // QUANTILE
            if (confidenceThreshold.compareTo(BigDecimal.ZERO) < 0 || confidenceThreshold.compareTo(new BigDecimal("100")) > 0) {
                return ResponseEntity.badRequest().body("QUANTILE 모드: confidenceThreshold must be between 0 and 100");
            }
        }

        if (positionSizePercent != null && (positionSizePercent.compareTo(BigDecimal.ZERO) < 0 || positionSizePercent.compareTo(new BigDecimal("100")) > 0)) {
            return ResponseEntity.badRequest().body("positionSizePercent must be between 0 and 100");
        }

        BacktestRequest request = new BacktestRequest(foldNumber, initialCapital, confidenceThreshold, confidenceColumn, thresholdMode, positionSizePercent);
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
     * @param confidenceThreshold 신뢰도 임계값 (기본값: 0.1)
     * @param confidenceColumn 사용할 컬럼 (CONFIDENCE 또는 PRED_PROBA_UP, 기본값: CONFIDENCE)
     * @param thresholdMode 임계값 모드 (FIXED 또는 QUANTILE, 기본값: FIXED)
     * @param positionSizePercent 포지션 크기 (0~100%), null이면 Kelly Criterion 자동 계산
     * @return Fold별 결과 및 전체 요약
     */
    @GetMapping("/run-sequential")
    public ResponseEntity<?> runSequentialBacktest(
        @RequestParam(required = false, defaultValue = "1") Integer startFold,
        @RequestParam(required = false, defaultValue = "7") Integer endFold,
        @RequestParam(required = false, defaultValue = "10000") BigDecimal initialCapital,
        @RequestParam(required = false, defaultValue = "0.1") BigDecimal confidenceThreshold,
        @RequestParam(required = false, defaultValue = "CONFIDENCE") ConfidenceColumn confidenceColumn,
        @RequestParam(required = false, defaultValue = "FIXED") ThresholdMode thresholdMode,
        @RequestParam(required = false) BigDecimal positionSizePercent
    ) {
        log.info("연속 백테스팅 API 호출: Fold {} ~ {}, initialCapital={}, confidenceThreshold={}, confidenceColumn={}, thresholdMode={}, positionSizePercent={}",
            startFold, endFold, initialCapital, confidenceThreshold, confidenceColumn, thresholdMode, positionSizePercent);

        // 입력 검증
        if (startFold < 1 || startFold > 8 || endFold < 1 || endFold > 8) {
            return ResponseEntity.badRequest().body("Fold numbers must be between 1 and 8");
        }
        if (startFold > endFold) {
            return ResponseEntity.badRequest().body("startFold must be less than or equal to endFold");
        }
        if (initialCapital.compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body("initialCapital must be positive");
        }

        // 임계값 범위 검증 (컬럼과 모드에 따라 다름)
        BigDecimal maxFixed = confidenceColumn == ConfidenceColumn.CONFIDENCE ? new BigDecimal("0.5") : BigDecimal.ONE;
        if (thresholdMode == ThresholdMode.FIXED) {
            if (confidenceThreshold.compareTo(BigDecimal.ZERO) < 0 || confidenceThreshold.compareTo(maxFixed) > 0) {
                return ResponseEntity.badRequest().body("FIXED 모드: confidenceThreshold must be between 0 and " + maxFixed);
            }
        } else { // QUANTILE
            if (confidenceThreshold.compareTo(BigDecimal.ZERO) < 0 || confidenceThreshold.compareTo(new BigDecimal("100")) > 0) {
                return ResponseEntity.badRequest().body("QUANTILE 모드: confidenceThreshold must be between 0 and 100");
            }
        }

        if (positionSizePercent != null && (positionSizePercent.compareTo(BigDecimal.ZERO) < 0 || positionSizePercent.compareTo(new BigDecimal("100")) > 0)) {
            return ResponseEntity.badRequest().body("positionSizePercent must be between 0 and 100");
        }

        SequentialBacktestResponse response = backtestService.runSequentialBacktest(
            startFold, endFold, initialCapital, confidenceThreshold, confidenceColumn, thresholdMode, positionSizePercent
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Fold 정보 조회 API
     */
    @GetMapping("/fold/{foldNumber}")
    public ResponseEntity<?> getFoldInfo(@PathVariable Integer foldNumber) {
        try {
            FoldConfig foldConfig = FoldConfig.getFold(foldNumber);

            FoldInfo info = new FoldInfo(
                foldConfig.getFoldNumber(),
                foldConfig.getStartDate().toString(),
                foldConfig.getEndDate().toString(),
                foldConfig.getRegime()
            );

            return ResponseEntity.ok(info);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid fold number: " + foldNumber);
        }
    }

    // Fold 정보 DTO
    record FoldInfo(
        int foldNumber,
        String startDate,
        String endDate,
        String regime
    ) {}

    /**
     * Take Profit / Stop Loss 백테스팅 실행 API (단일 모델/Fold)
     * - 1분봉 데이터를 활용한 정밀 매매 시뮬레이션
     * - pred_proba_up 임계값 이상인 거래만 진입
     * - Kelly Criterion × Confidence 포지션 사이징
     * - 8일 보유 기간 동안 TP/SL 추적
     *
     * @param request 백테스팅 요청 파라미터
     * @return 백테스팅 결과 (거래 통계, 수익률, 리스크 지표, 거래 내역)
     */
    @PostMapping("/tp-sl/run")
    public ResponseEntity<?> runTpSlBacktest(@RequestBody TakeProfitStopLossBacktestRequest request) {
        log.info("TP/SL 백테스팅 API 호출: Model={}, Fold={}, Threshold={}, HoldingDays={}",
            request.getModelName(), request.getFoldNumber(), request.getPredProbaThreshold(), request.getHoldingPeriodDays());

        // 입력 검증
        if (request.getFoldNumber() == null || request.getFoldNumber() < 1 || request.getFoldNumber() > 8) {
            return ResponseEntity.badRequest().body("foldNumber must be between 1 and 8");
        }
        if (request.getModelName() == null || request.getModelName().isBlank()) {
            return ResponseEntity.badRequest().body("modelName is required");
        }
        if (request.getInitialCapital() == null || request.getInitialCapital().compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body("initialCapital must be positive");
        }
        if (request.getPredProbaThreshold() == null ||
            request.getPredProbaThreshold().compareTo(BigDecimal.ZERO) < 0 ||
            request.getPredProbaThreshold().compareTo(BigDecimal.ONE) > 0) {
            return ResponseEntity.badRequest().body("predProbaThreshold must be between 0 and 1");
        }
        if (request.getHoldingPeriodDays() == null || request.getHoldingPeriodDays() <= 0) {
            return ResponseEntity.badRequest().body("holdingPeriodDays must be positive");
        }

        try {
            TakeProfitStopLossBacktestResponse response = tpSlBacktestService.runBacktest(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("TP/SL 백테스팅 실패: Model={}, Fold={}", request.getModelName(), request.getFoldNumber(), e);
            return ResponseEntity.internalServerError().body("Backtest failed: " + e.getMessage());
        }
    }

    /**
     * Take Profit / Stop Loss 백테스팅 배치 실행 API (다중 모델/Fold)
     * - 여러 모델과 Fold에 대해 순차적으로 백테스팅 실행
     * - 진행 상황 로깅 제공
     *
     * @param batchRequest 배치 요청 파라미터
     * @return 모든 백테스팅 결과 리스트
     */
    @PostMapping("/tp-sl/run-batch")
    public ResponseEntity<?> runTpSlBatchBacktest(@RequestBody TpSlBatchRequest batchRequest) {
        log.info("TP/SL 배치 백테스팅 API 호출: Models={}, Folds={}", batchRequest.modelNames, batchRequest.foldNumbers);

        // 입력 검증
        if (batchRequest.modelNames == null || batchRequest.modelNames.isEmpty()) {
            return ResponseEntity.badRequest().body("modelNames is required");
        }
        if (batchRequest.foldNumbers == null || batchRequest.foldNumbers.isEmpty()) {
            return ResponseEntity.badRequest().body("foldNumbers is required");
        }

        try {
            java.util.List<TakeProfitStopLossBacktestResponse> results = new java.util.ArrayList<>();
            int totalCombinations = batchRequest.modelNames.size() * batchRequest.foldNumbers.size();
            int currentIndex = 0;

            for (String modelName : batchRequest.modelNames) {
                for (Integer foldNumber : batchRequest.foldNumbers) {
                    currentIndex++;
                    log.info("=== 배치 진행 중: {}/{} (Model={}, Fold={}) ===",
                        currentIndex, totalCombinations, modelName, foldNumber);

                    TakeProfitStopLossBacktestRequest request = TakeProfitStopLossBacktestRequest.builder()
                        .foldNumber(foldNumber)
                        .modelName(modelName)
                        .initialCapital(batchRequest.initialCapital != null ? batchRequest.initialCapital : new BigDecimal("10000"))
                        .predProbaThreshold(batchRequest.predProbaThreshold != null ? batchRequest.predProbaThreshold : new BigDecimal("0.6"))
                        .holdingPeriodDays(batchRequest.holdingPeriodDays != null ? batchRequest.holdingPeriodDays : 8)
                        .build();

                    try {
                        TakeProfitStopLossBacktestResponse response = tpSlBacktestService.runBacktest(request);
                        results.add(response);
                        log.info("✓ 완료: Model={}, Fold={}, Return={}%",
                            modelName, foldNumber, response.getTotalReturnPct());
                    } catch (Exception e) {
                        log.error("✗ 실패: Model={}, Fold={}, Error={}",
                            modelName, foldNumber, e.getMessage());
                        // 실패한 경우에도 계속 진행
                    }
                }
            }

            log.info("=== 배치 백테스팅 완료: 총 {}건 중 {}건 성공 ===", totalCombinations, results.size());
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("배치 백테스팅 실패", e);
            return ResponseEntity.internalServerError().body("Batch backtest failed: " + e.getMessage());
        }
    }

    // TP/SL 배치 요청 DTO
    record TpSlBatchRequest(
        java.util.List<String> modelNames,
        java.util.List<Integer> foldNumbers,
        BigDecimal initialCapital,
        BigDecimal predProbaThreshold,
        Integer holdingPeriodDays
    ) {}
}
