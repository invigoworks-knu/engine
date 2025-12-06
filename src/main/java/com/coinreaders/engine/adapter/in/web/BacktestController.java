package com.coinreaders.engine.adapter.in.web;

import com.coinreaders.engine.application.backtest.AsyncBacktestService;
import com.coinreaders.engine.application.backtest.BuyAndHoldBacktestService;
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
    private final AsyncBacktestService asyncBacktestService;

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
                        log.info("✓ 완료: Model={}, Fold={}, 초기자본={}원 → 최종자본={}원 (수익률 {}%)",
                            modelName, foldNumber,
                            response.getInitialCapital(),
                            response.getFinalCapital(),
                            response.getTotalReturnPct());
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
                batchRequest.holdingPeriodDays != null ? batchRequest.holdingPeriodDays : 8
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
        log.info("Buy & Hold 배치 백테스팅 API 호출: Folds={}", batchRequest.foldNumbers);

        // 입력 검증
        if (batchRequest.foldNumbers == null || batchRequest.foldNumbers.isEmpty()) {
            return ResponseEntity.badRequest().body("foldNumbers is required");
        }

        try {
            java.util.List<TakeProfitStopLossBacktestResponse> results = new java.util.ArrayList<>();

            for (Integer foldNumber : batchRequest.foldNumbers) {
                log.info("=== Buy & Hold 실행 중: Fold={} ===", foldNumber);

                BuyAndHoldBacktestRequest request = BuyAndHoldBacktestRequest.builder()
                    .foldNumber(foldNumber)
                    .initialCapital(batchRequest.initialCapital != null ? batchRequest.initialCapital : new BigDecimal("10000"))
                    .build();

                try {
                    TakeProfitStopLossBacktestResponse response = buyAndHoldBacktestService.runBacktest(request);
                    results.add(response);
                    log.info("✓ 완료: Fold={}, 초기자본={}원 → 최종자본={}원 (수익률 {}%)",
                        foldNumber,
                        response.getInitialCapital(),
                        response.getFinalCapital(),
                        response.getTotalReturnPct());
                } catch (Exception e) {
                    log.error("✗ 실패: Fold={}, Error={}", foldNumber, e.getMessage());
                    // 실패한 경우에도 계속 진행
                }
            }

            log.info("=== Buy & Hold 배치 백테스팅 완료: 총 {}건 중 {}건 성공 ===",
                batchRequest.foldNumbers.size(), results.size());
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
}
