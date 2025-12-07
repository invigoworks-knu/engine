package com.coinreaders.engine.adapter.in.web;

import com.coinreaders.engine.application.backtest.AsyncBacktestService;
import com.coinreaders.engine.application.backtest.BuyAndHoldBacktestService;
import com.coinreaders.engine.application.backtest.CusumSignalBacktestService;
import com.coinreaders.engine.application.backtest.RuleBasedBacktestService;
import com.coinreaders.engine.application.backtest.TakeProfitStopLossBacktestService;
import com.coinreaders.engine.application.backtest.dto.BuyAndHoldBacktestRequest;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestRequest;
import com.coinreaders.engine.application.backtest.dto.TakeProfitStopLossBacktestResponse;
import com.coinreaders.engine.domain.entity.BacktestJob;
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

    private final TakeProfitStopLossBacktestService tpSlBacktestService;
    private final BuyAndHoldBacktestService buyAndHoldBacktestService;
    private final RuleBasedBacktestService ruleBasedBacktestService;
    private final AsyncBacktestService asyncBacktestService;
    private final CusumSignalBacktestService cusumSignalBacktestService;

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

            // Fold 번호를 오름차순으로 정렬 (1→2→3→...→7)
            java.util.List<Integer> sortedFolds = new java.util.ArrayList<>(batchRequest.foldNumbers);
            java.util.Collections.sort(sortedFolds);

            // 기본값 설정
            BigDecimal initialCapitalBase = batchRequest.initialCapital != null ? batchRequest.initialCapital : new BigDecimal("10000");
            BigDecimal predProbaThreshold = batchRequest.predProbaThreshold != null ? batchRequest.predProbaThreshold : new BigDecimal("0.6");
            Integer holdingPeriodDays = batchRequest.holdingPeriodDays != null ? batchRequest.holdingPeriodDays : 8;
            com.coinreaders.engine.application.backtest.dto.PositionSizingStrategy positionSizingStrategy =
                batchRequest.positionSizingStrategy != null ? batchRequest.positionSizingStrategy :
                    com.coinreaders.engine.application.backtest.dto.PositionSizingStrategy.CONSERVATIVE_KELLY;

            for (String modelName : batchRequest.modelNames) {
                // 각 모델별로 초기 자본으로 시작
                BigDecimal currentCapital = initialCapitalBase;
                BigDecimal modelStartCapital = initialCapitalBase;

                log.info("▶ 모델 [{}] 시작 - 초기 자본: {}원", modelName, currentCapital);

                for (Integer foldNumber : sortedFolds) {
                    currentIndex++;
                    log.info("=== 배치 진행 중: {}/{} (Model={}, Fold={}, 시작자본={}원) ===",
                        currentIndex, totalCombinations, modelName, foldNumber, currentCapital);

                    TakeProfitStopLossBacktestRequest request = TakeProfitStopLossBacktestRequest.builder()
                        .foldNumber(foldNumber)
                        .modelName(modelName)
                        .initialCapital(currentCapital) // 이전 Fold의 최종 자본 사용
                        .predProbaThreshold(predProbaThreshold)
                        .holdingPeriodDays(holdingPeriodDays)
                        .positionSizingStrategy(positionSizingStrategy)
                        .build();

                    try {
                        TakeProfitStopLossBacktestResponse response = tpSlBacktestService.runBacktest(request);
                        results.add(response);

                        // 다음 Fold를 위해 최종 자본 업데이트
                        currentCapital = response.getFinalCapital();

                        log.info("✓ 완료: Model={}, Fold={}, {}원 → {}원 (수익률 {}%)",
                            modelName, foldNumber,
                            response.getInitialCapital(),
                            response.getFinalCapital(),
                            response.getTotalReturnPct());
                    } catch (Exception e) {
                        log.error("✗ 실패: Model={}, Fold={}, Error={}",
                            modelName, foldNumber, e.getMessage());
                        // 실패한 경우에도 다음 Fold는 현재 자본으로 계속 진행
                    }
                }

                // 모델별 최종 결과 로그
                BigDecimal modelTotalReturn = currentCapital.subtract(modelStartCapital);
                BigDecimal modelReturnPct = modelTotalReturn.divide(modelStartCapital, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));

                log.info("◀ 모델 [{}] 완료 - {}원 → {}원 (누적 수익률 {}%)",
                    modelName, modelStartCapital, currentCapital, modelReturnPct);
            }

            // ===== 벤치마크 자동 추가 =====
            log.info("=== 벤치마크 전략 실행 시작 (Buy & Hold, Rule-Based) ===");

            // Buy & Hold 벤치마크
            try {
                BigDecimal benchmarkCapital = initialCapitalBase;
                for (Integer foldNumber : sortedFolds) {
                    log.info("▶ Buy & Hold 실행 중: Fold={}, 초기자본={}원", foldNumber, benchmarkCapital);

                    BuyAndHoldBacktestRequest bhRequest = BuyAndHoldBacktestRequest.builder()
                        .foldNumber(foldNumber)
                        .initialCapital(benchmarkCapital)
                        .build();

                    TakeProfitStopLossBacktestResponse bhResponse = buyAndHoldBacktestService.runBacktest(bhRequest);
                    results.add(bhResponse);
                    benchmarkCapital = bhResponse.getFinalCapital();

                    log.info("✓ Buy & Hold 완료: Fold={}, {}원 → {}원 (수익률 {}%)",
                        foldNumber, bhResponse.getInitialCapital(), bhResponse.getFinalCapital(), bhResponse.getTotalReturnPct());
                }
            } catch (Exception e) {
                log.error("Buy & Hold 벤치마크 실패", e);
            }

            // Rule-Based 벤치마크
            try {
                BigDecimal ruleBasedCapital = initialCapitalBase;
                for (Integer foldNumber : sortedFolds) {
                    log.info("▶ Rule-Based 실행 중: Fold={}, 초기자본={}원", foldNumber, ruleBasedCapital);

                    TakeProfitStopLossBacktestResponse rbResponse = ruleBasedBacktestService.runBacktest(foldNumber, ruleBasedCapital);
                    results.add(rbResponse);
                    ruleBasedCapital = rbResponse.getFinalCapital();

                    log.info("✓ Rule-Based 완료: Fold={}, {}원 → {}원 (수익률 {}%)",
                        foldNumber, rbResponse.getInitialCapital(), rbResponse.getFinalCapital(), rbResponse.getTotalReturnPct());
                }
            } catch (Exception e) {
                log.error("Rule-Based 벤치마크 실패", e);
            }

            log.info("=== 배치 백테스팅 완료: 총 {}건 (ML모델 + 벤치마크 포함) ===", results.size());
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
        Integer holdingPeriodDays,
        com.coinreaders.engine.application.backtest.dto.PositionSizingStrategy positionSizingStrategy
    ) {}

    /**
     * Take Profit / Stop Loss 배치 백테스팅 비동기 실행 API
     * - 백그라운드에서 실행하고 즉시 jobId 반환
     * - 진행 상황은 별도 API로 조회
     *
     * @param batchRequest 배치 요청 파라미터
     * @return jobId (작업 추적용)
     */
    @PostMapping("/tp-sl/run-batch-async")
    public ResponseEntity<?> runTpSlBatchBacktestAsync(@RequestBody TpSlBatchRequest batchRequest) {
        log.info("TP/SL 비동기 배치 백테스팅 API 호출: Models={}, Folds={}",
            batchRequest.modelNames, batchRequest.foldNumbers);

        // 입력 검증
        if (batchRequest.modelNames == null || batchRequest.modelNames.isEmpty()) {
            return ResponseEntity.badRequest().body("modelNames is required");
        }
        if (batchRequest.foldNumbers == null || batchRequest.foldNumbers.isEmpty()) {
            return ResponseEntity.badRequest().body("foldNumbers is required");
        }

        try {
            String jobId = asyncBacktestService.submitBatchBacktest(
                batchRequest.modelNames,
                batchRequest.foldNumbers,
                batchRequest.initialCapital != null ? batchRequest.initialCapital : new BigDecimal("10000"),
                batchRequest.predProbaThreshold != null ? batchRequest.predProbaThreshold : new BigDecimal("0.6"),
                batchRequest.holdingPeriodDays != null ? batchRequest.holdingPeriodDays : 8,
                batchRequest.positionSizingStrategy != null ? batchRequest.positionSizingStrategy :
                    com.coinreaders.engine.application.backtest.dto.PositionSizingStrategy.CONSERVATIVE_KELLY
            );

            log.info("비동기 작업 등록 완료: jobId={}", jobId);
            return ResponseEntity.ok(new AsyncJobResponse(jobId, "배치 백테스팅 작업이 시작되었습니다."));
        } catch (Exception e) {
            log.error("비동기 배치 백테스팅 등록 실패", e);
            return ResponseEntity.internalServerError().body("Failed to submit batch job: " + e.getMessage());
        }
    }

    /**
     * 백테스팅 작업 상태 조회 API
     *
     * @param jobId 작업 ID
     * @return 작업 상태 (PENDING, RUNNING, COMPLETED, FAILED)
     */
    @GetMapping("/tp-sl/job/{jobId}")
    public ResponseEntity<?> getJobStatus(@PathVariable String jobId) {
        try {
            BacktestJob job = asyncBacktestService.getJobStatus(jobId);

            JobStatusResponse response = new JobStatusResponse(
                job.getJobId(),
                job.getStatus().name(),
                job.getTotalTasks(),
                job.getCompletedTasks(),
                job.getFailedTasks(),
                job.getProgress(),
                job.getErrorMessage()
            );

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("작업 상태 조회 실패: jobId={}", jobId, e);
            return ResponseEntity.internalServerError().body("Failed to get job status: " + e.getMessage());
        }
    }

    /**
     * 완료된 작업의 결과 조회 API
     * Note: 현재는 결과를 DB에 저장하지 않으므로, 프론트엔드에서 동기 배치 API를 재호출해야 합니다.
     *
     * @param jobId 작업 ID
     * @return 백테스팅 결과 리스트
     */
    @GetMapping("/tp-sl/job/{jobId}/results")
    public ResponseEntity<?> getJobResults(@PathVariable String jobId) {
        try {
            // 현재는 빈 리스트 반환 (향후 개선 필요)
            return ResponseEntity.ok("작업이 완료되었습니다. 동기 배치 API(/api/backtest/tp-sl/run-batch)를 다시 호출하여 결과를 조회하세요.");
        } catch (Exception e) {
            log.error("작업 결과 조회 실패: jobId={}", jobId, e);
            return ResponseEntity.internalServerError().body("Failed to get job results: " + e.getMessage());
        }
    }

    // 비동기 작업 응답 DTO
    record AsyncJobResponse(String jobId, String message) {}

    // 작업 상태 응답 DTO
    record JobStatusResponse(
        String jobId,
        String status,
        int totalTasks,
        int completedTasks,
        int failedTasks,
        int progress,
        String errorMessage
    ) {}

    /**
     * Buy & Hold 백테스팅 실행 API (단일 Fold)
     * - Fold 시작 시점에 전액 매수, 종료 시점에 전액 매도
     * - AI 모델 성능 비교를 위한 벤치마크 전략
     *
     * @param request 백테스팅 요청 파라미터
     * @return 백테스팅 결과
     */
    @PostMapping("/buy-hold/run")
    public ResponseEntity<?> runBuyHoldBacktest(@RequestBody BuyAndHoldBacktestRequest request) {
        log.info("Buy & Hold 백테스팅 API 호출: Fold={}", request.getFoldNumber());

        // 입력 검증
        if (request.getFoldNumber() == null || request.getFoldNumber() < 1 || request.getFoldNumber() > 8) {
            return ResponseEntity.badRequest().body("foldNumber must be between 1 and 8");
        }
        if (request.getInitialCapital() == null || request.getInitialCapital().compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body("initialCapital must be positive");
        }

        try {
            TakeProfitStopLossBacktestResponse response = buyAndHoldBacktestService.runBacktest(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Buy & Hold 백테스팅 실패: Fold={}", request.getFoldNumber(), e);
            return ResponseEntity.internalServerError().body("Backtest failed: " + e.getMessage());
        }
    }

    /**
     * Buy & Hold 백테스팅 배치 실행 API (다중 Fold)
     * - 여러 Fold에 대해 순차적으로 백테스팅 실행
     *
     * @param batchRequest 배치 요청 파라미터
     * @return 모든 백테스팅 결과 리스트
     */
    @PostMapping("/buy-hold/run-batch")
    public ResponseEntity<?> runBuyHoldBatchBacktest(@RequestBody BuyHoldBatchRequest batchRequest) {
        log.info("Buy & Hold 배치 백테스팅 API 호출 (연속 실행): Folds={}", batchRequest.foldNumbers);

        // 입력 검증
        if (batchRequest.foldNumbers == null || batchRequest.foldNumbers.isEmpty()) {
            return ResponseEntity.badRequest().body("foldNumbers is required");
        }

        try {
            java.util.List<TakeProfitStopLossBacktestResponse> results = new java.util.ArrayList<>();

            // 연속 실행: 이전 Fold의 최종 자본을 다음 Fold의 초기 자본으로 사용
            BigDecimal currentCapital = batchRequest.initialCapital != null ?
                batchRequest.initialCapital : new BigDecimal("10000");

            for (Integer foldNumber : batchRequest.foldNumbers) {
                log.info("=== Buy & Hold 실행 중: Fold={}, 초기자본={}원 ===", foldNumber, currentCapital);

                BuyAndHoldBacktestRequest request = BuyAndHoldBacktestRequest.builder()
                    .foldNumber(foldNumber)
                    .initialCapital(currentCapital)
                    .build();

                try {
                    TakeProfitStopLossBacktestResponse response = buyAndHoldBacktestService.runBacktest(request);
                    results.add(response);

                    // 다음 Fold의 초기 자본 = 현재 Fold의 최종 자본
                    currentCapital = response.getFinalCapital();

                    log.info("✓ 완료: Fold={}, {}원 → {}원 (수익률 {}%)",
                        foldNumber,
                        response.getInitialCapital(),
                        response.getFinalCapital(),
                        response.getTotalReturnPct());
                } catch (Exception e) {
                    log.error("✗ 실패: Fold={}, Error={}", foldNumber, e.getMessage());
                    // 실패한 경우 이전 자본 유지하고 계속 진행
                }
            }

            log.info("=== Buy & Hold 배치 백테스팅 완료: 총 {}건 중 {}건 성공, 최종자본={}원 ===",
                batchRequest.foldNumbers.size(), results.size(), currentCapital);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Buy & Hold 배치 백테스팅 실패", e);
            return ResponseEntity.internalServerError().body("Batch backtest failed: " + e.getMessage());
        }
    }

    // Buy & Hold 배치 요청 DTO
    record BuyHoldBatchRequest(
        java.util.List<Integer> foldNumbers,
        BigDecimal initialCapital
    ) {}

    // ===== CUSUM 신호 기반 백테스팅 API =====

    /**
     * CUSUM 신호 데이터 상태 조회 API
     * - 로드 여부, 신호 개수, 사용 가능한 전략/모델 목록 확인
     */
    @GetMapping("/cusum/status")
    public ResponseEntity<?> getCusumStatus() {
        log.info("CUSUM 신호 상태 조회 API 호출");

        java.util.Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("dataLoaded", cusumSignalBacktestService.isDataLoaded());
        status.put("totalSignals", cusumSignalBacktestService.getSignalCount());

        if (cusumSignalBacktestService.isDataLoaded()) {
            status.put("availableStrategies", cusumSignalBacktestService.getAvailableStrategies());
            status.put("availableModels", cusumSignalBacktestService.getAvailableModels());
            status.put("availableFolds", cusumSignalBacktestService.getAvailableFolds());
        }

        return ResponseEntity.ok(status);
    }

    /**
     * CUSUM 신호 CSV 데이터 수동 로드 API
     */
    @PostMapping("/cusum/load")
    public ResponseEntity<?> loadCusumData() {
        log.info("CUSUM 신호 CSV 로드 API 호출");

        try {
            int count = cusumSignalBacktestService.loadCsvData();
            return ResponseEntity.ok(java.util.Map.of(
                "success", true,
                "loadedSignals", count,
                "message", "CUSUM 신호 데이터 로드 완료"
            ));
        } catch (Exception e) {
            log.error("CUSUM CSV 로드 실패", e);
            return ResponseEntity.internalServerError().body(java.util.Map.of(
                "success", false,
                "error", e.getMessage(),
                "hint", "파일 경로: src/main/resources/cusum_signals/backend_signals_master.csv"
            ));
        }
    }

    /**
     * CUSUM 신호 전략별 요약 통계 조회 API
     */
    @GetMapping("/cusum/summary")
    public ResponseEntity<?> getCusumSummary() {
        log.info("CUSUM 전략 요약 조회 API 호출");
        return ResponseEntity.ok(cusumSignalBacktestService.getStrategySummary());
    }

    /**
     * CUSUM 신호 기반 백테스팅 실행 API (단일 전략)
     *
     * @param request 백테스팅 요청 파라미터
     * @return 백테스팅 결과 (기존 TakeProfitStopLossBacktestResponse와 동일 형식)
     */
    @PostMapping("/cusum/run")
    public ResponseEntity<?> runCusumBacktest(@RequestBody CusumBacktestRequest request) {
        log.info("CUSUM 백테스팅 API 호출: Fold={}, Strategy={}, Model={}",
            request.foldNumber, request.strategy, request.model);

        if (!cusumSignalBacktestService.isDataLoaded()) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                "error", "CUSUM 신호 데이터가 로드되지 않았습니다",
                "hint", "POST /api/backtest/cusum/load 를 먼저 호출하거나 CSV 파일을 배치해주세요"
            ));
        }

        try {
            BigDecimal initialCapital = request.initialCapital != null ?
                request.initialCapital : new BigDecimal("10000000");

            TakeProfitStopLossBacktestResponse response = cusumSignalBacktestService.runBacktest(
                request.foldNumber,
                request.strategy,
                request.model,
                initialCapital
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("CUSUM 백테스팅 실패", e);
            return ResponseEntity.internalServerError().body("CUSUM Backtest failed: " + e.getMessage());
        }
    }

    /**
     * CUSUM 신호 기반 백테스팅 배치 실행 API
     * - 여러 전략/모델에 대해 순차적으로 실행
     * - 기존 ML 모델 결과와 함께 반환하여 비교 가능
     *
     * @param batchRequest 배치 요청 파라미터
     * @return 모든 백테스팅 결과 리스트
     */
    @PostMapping("/cusum/run-batch")
    public ResponseEntity<?> runCusumBatchBacktest(@RequestBody CusumBatchRequest batchRequest) {
        log.info("CUSUM 배치 백테스팅 API 호출: Strategies={}, Models={}, Folds={}",
            batchRequest.strategies, batchRequest.models, batchRequest.foldNumbers);

        if (!cusumSignalBacktestService.isDataLoaded()) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                "error", "CUSUM 신호 데이터가 로드되지 않았습니다",
                "hint", "POST /api/backtest/cusum/load 를 먼저 호출하거나 CSV 파일을 배치해주세요"
            ));
        }

        try {
            java.util.List<TakeProfitStopLossBacktestResponse> results = new java.util.ArrayList<>();
            BigDecimal initialCapital = batchRequest.initialCapital != null ?
                batchRequest.initialCapital : new BigDecimal("10000");

            // 전략 목록 결정
            java.util.List<String> strategies = batchRequest.strategies;
            if (strategies == null || strategies.isEmpty()) {
                strategies = cusumSignalBacktestService.getAvailableStrategies();
            }

            // 모델 목록 결정 (null/빈 리스트면 전체 모델)
            java.util.List<String> models = batchRequest.models;
            if (models == null || models.isEmpty()) {
                models = cusumSignalBacktestService.getAvailableModels();
            }

            // Fold 목록 결정
            java.util.List<Integer> folds = batchRequest.foldNumbers;
            if (folds == null || folds.isEmpty()) {
                folds = cusumSignalBacktestService.getAvailableFolds();
            }

            // 배치 실행: 전략 × 모델 × Fold 조합
            for (String strategy : strategies) {
                for (String model : models) {
                    BigDecimal currentCapital = initialCapital;

                    for (Integer fold : folds) {
                        log.info("▶ CUSUM 실행: Strategy={}, Model={}, Fold={}, Capital={}",
                            strategy, model, fold, currentCapital);

                        try {
                            TakeProfitStopLossBacktestResponse response =
                                cusumSignalBacktestService.runBacktest(fold, strategy, model, currentCapital);

                            results.add(response);
                            currentCapital = response.getFinalCapital();

                            log.info("✓ 완료: Strategy={}, Model={}, Fold={}, {}원 → {}원 ({}%)",
                                strategy, model, fold,
                                response.getInitialCapital(),
                                response.getFinalCapital(),
                                response.getTotalReturnPct());
                        } catch (Exception e) {
                            log.error("✗ 실패: Strategy={}, Model={}, Fold={}, Error={}",
                                strategy, model, fold, e.getMessage());
                        }
                    }
                }
            }

            log.info("=== CUSUM 배치 백테스팅 완료: 총 {}건 ===", results.size());
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("CUSUM 배치 백테스팅 실패", e);
            return ResponseEntity.internalServerError().body("CUSUM Batch backtest failed: " + e.getMessage());
        }
    }

    // CUSUM 백테스팅 요청 DTO
    record CusumBacktestRequest(
        Integer foldNumber,          // Fold 번호 (null이면 전체)
        String strategy,             // 전략명 (예: "target_4h_LGBM", null이면 전체)
        String model,                // 모델명 (예: "LGBM", null이면 전체)
        BigDecimal initialCapital    // 초기 자본 (기본값: 1000만원)
    ) {}

    // CUSUM 배치 요청 DTO
    record CusumBatchRequest(
        java.util.List<String> strategies,      // 전략 목록 (null이면 전체)
        java.util.List<String> models,          // 모델 목록 (null이면 전체)
        java.util.List<Integer> foldNumbers,    // Fold 목록 (null이면 전체)
        BigDecimal initialCapital               // 초기 자본
    ) {}
}
