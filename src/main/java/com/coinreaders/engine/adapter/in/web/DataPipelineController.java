package com.coinreaders.engine.adapter.in.web;

import com.coinreaders.engine.application.DataPipelineService;
import com.coinreaders.engine.application.AiPredictionDataService;
import com.coinreaders.engine.domain.entity.HistoricalAiPrediction;
import com.coinreaders.engine.domain.repository.HistoricalOhlcvRepository;
import com.coinreaders.engine.domain.repository.HistoricalAiPredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/data")
@RequiredArgsConstructor
public class DataPipelineController {

    private final DataPipelineService dataPipelineService;
    private final AiPredictionDataService aiPredictionDataService;
    private final HistoricalOhlcvRepository ohlcvRepository;
    private final HistoricalAiPredictionRepository aiPredictionRepository;

    /**
     * 데이터 적재 상태 확인 API
     */
    @GetMapping("/status")
    public ResponseEntity<DataStatus> getDataStatus() {
        try {
            // OHLCV 데이터 개수 확인
            long ohlcvCount = ohlcvRepository.count();

            // AI 예측 데이터 개수 확인
            long aiPredictionCount = aiPredictionRepository.count();

            // 데이터 적재 여부 판단 (임계값 기준)
            boolean ohlcvLoaded = ohlcvCount >= 2000; // 최소 2000개 이상
            boolean aiPredictionsLoaded = aiPredictionCount >= 1000; // 최소 1000개 이상 (다중 모델: 10,512개)

            log.info("데이터 상태 조회: OHLCV {}건 ({}), AI 예측 {}건 ({})",
                ohlcvCount, ohlcvLoaded ? "적재완료" : "미적재",
                aiPredictionCount, aiPredictionsLoaded ? "적재완료" : "미적재");

            DataStatus status = new DataStatus(
                ohlcvLoaded,
                aiPredictionsLoaded,
                ohlcvCount,
                aiPredictionCount
            );

            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("데이터 상태 조회 실패", e);
            return ResponseEntity.internalServerError().build();
        }
    }

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
            // 중복 적재 방지: 이미 충분한 데이터가 있는지 확인
            long existingCount = ohlcvRepository.count();
            if (existingCount >= 2000) {
                log.info("OHLCV 데이터가 이미 적재되어 있습니다. ({}건)", existingCount);
                return ResponseEntity.ok(
                    String.format("OHLCV 데이터가 이미 적재되어 있습니다. (총 %d건)", existingCount)
                );
            }

            dataPipelineService.loadAllHistoricalOhlcv("KRW-ETH", "2017-11-09");
            return ResponseEntity.ok("All historical data initialization completed successfully.");
        } catch (Exception e) {
            log.error("Failed to load all historical data", e);
            return ResponseEntity.internalServerError().body("Failed to load data: " + e.getMessage());
        }
    }

    /**
     * (UC-001) CSV 파일로 AI 예측 데이터를 적재합니다.
     * [Training Set] GRU - Fold 1-7 (Walk-forward Validation 세트)
     * - 2023-01-06 ~ 2025-10-20
     * - 각 fold: 120개 데이터 (총 840개)
     * - 시간 순서대로 연속된 검증 세트
     */
    @PostMapping("/init-ai-predictions-training")
    public ResponseEntity<String> initializeAiPredictionsTraining() {
        try {
            int totalRecords = 0;

            // Fold 1-7: Walk-forward validation 세트
            for (int fold = 1; fold <= 7; fold++) {
                aiPredictionDataService.loadAiPredictionsFromCsv(fold);
                totalRecords += 120;
                log.info("Loaded Fold {} successfully (120 records)", fold);
            }

            return ResponseEntity.ok(
                String.format("AI Prediction Training data (GRU-f1~f7) initialization completed. Total %d records loaded.", totalRecords)
            );
        } catch (Exception e) {
            log.error("Failed to load AI prediction training data", e);
            return ResponseEntity.internalServerError().body("Failed to load AI training data: " + e.getMessage());
        }
    }

    /**
     * (UC-001) CSV 파일로 AI 예측 데이터를 적재합니다.
     * [Test Set] GRU - Fold 8 (Final Test 세트)
     * - 2025-01-31 ~ 2025-10-20
     * - 264개 데이터
     * - 최종 모델 성능 평가용 홀드아웃 세트
     */
    @PostMapping("/init-ai-predictions-test")
    public ResponseEntity<String> initializeAiPredictionsTest() {
        try {
            aiPredictionDataService.loadAiPredictionsFromCsv(8);

            return ResponseEntity.ok("AI Prediction Test data (Fold 8) initialization completed successfully. (264 records)");
        } catch (Exception e) {
            log.error("Failed to load AI prediction test data", e);
            return ResponseEntity.internalServerError().body("Failed to load AI test data: " + e.getMessage());
        }
    }

    /**
     * (UC-001) CSV 파일로 AI 예측 데이터를 적재합니다.
     * [All] GRU - Fold 1-8 전체
     * - Training (Fold 1-7): 840개
     * - Test (Fold 8): 264개
     * - 총 1,104개 데이터
     */
    @PostMapping("/init-ai-predictions-all")
    public ResponseEntity<String> initializeAllAiPredictions() {
        try {
            // 중복 적재 방지: 이미 충분한 데이터가 있는지 확인
            long existingCount = aiPredictionRepository.count();
            if (existingCount >= 1000) {
                log.info("AI 예측 데이터가 이미 적재되어 있습니다. ({}건)", existingCount);
                return ResponseEntity.ok(
                    String.format("AI 예측 데이터가 이미 적재되어 있습니다. (총 %d건)", existingCount)
                );
            }

            int totalRecords = 0;

            // Fold 1-7: 각각 120개 데이터
            for (int fold = 1; fold <= 7; fold++) {
                aiPredictionDataService.loadAiPredictionsFromCsv(fold);
                totalRecords += 120;
                log.info("Loaded Fold {} successfully (120 records)", fold);
            }

            // Fold 8: 264개 데이터
            aiPredictionDataService.loadAiPredictionsFromCsv(8);
            totalRecords += 264;
            log.info("Loaded Fold 8 successfully (264 records)");

            return ResponseEntity.ok(
                String.format("All AI Prediction data (GRU-f1~f8) initialization completed. Total %d records loaded.", totalRecords)
            );
        } catch (Exception e) {
            log.error("Failed to load AI prediction data", e);
            return ResponseEntity.internalServerError().body("Failed to load AI data: " + e.getMessage());
        }
    }

    /**
     * (신규) 다중 모델 AI 예측 데이터 적재 - direction_l8_p1.5_s1.0 폴더
     * - 12개 ML 모델 (GRU, LSTM, BiLSTM, XGBoost, LightGBM, CatBoost, etc.)
     * - Look-ahead 8일 예측
     * - 익절 1.5%, 손절 1.0% 포함
     * - Fold 1-7: 각 95건 × 12모델 = 7,980건
     * - Fold 8: 211건 × 12모델 = 2,532건
     * - 총 10,512건
     */
    @PostMapping("/init-multi-model-predictions-all")
    public ResponseEntity<String> initializeAllMultiModelPredictions() {
        try {
            log.info("=== 다중 모델 AI 예측 데이터 적재 시작 ===");

            // 중복 적재 방지: 특정 모델의 데이터 개수 확인 (XGBoost 기준)
            long existingCount = aiPredictionRepository.countByMarketAndModelName("KRW-ETH", "XGBoost");
            log.info("기존 XGBoost 데이터: {}건", existingCount);

            if (existingCount >= 800) {  // 876건 중 800건 이상이면 이미 적재됨
                long totalCount = aiPredictionRepository.count();
                log.info("다중 모델 AI 예측 데이터가 이미 적재되어 있습니다. (총 {}건)", totalCount);
                return ResponseEntity.ok(
                    String.format("다중 모델 AI 예측 데이터가 이미 적재되어 있습니다. (총 %d건)", totalCount)
                );
            }

            int totalRecords = 0;

            // Fold 1-7: walk_forward_rolling_reverse
            for (int fold = 1; fold <= 7; fold++) {
                log.info("Fold {} 적재 시작...", fold);
                int foldCount = aiPredictionDataService.loadMultiModelPredictionsFromCsv(fold);
                totalRecords += foldCount;
                log.info("✓ Fold {} 완료 (실제 {}건 적재)", fold, foldCount);
            }

            // Fold 8: final_holdout
            log.info("Fold 8 (Final Holdout) 적재 시작...");
            int fold8Count = aiPredictionDataService.loadMultiModelPredictionsFromCsv(8);
            totalRecords += fold8Count;
            log.info("✓ Fold 8 완료 (실제 {}건 적재)", fold8Count);

            // 최종 확인
            long finalCount = aiPredictionRepository.count();
            log.info("=== 적재 완료: 총 {}건 (DB 확인: {}건) ===", totalRecords, finalCount);

            return ResponseEntity.ok(
                String.format("다중 모델 AI 예측 데이터 적재 완료. 총 %d건 (12개 모델 × 876개 예측)", finalCount)
            );
        } catch (Exception e) {
            log.error("Failed to load multi-model AI prediction data", e);
            return ResponseEntity.internalServerError().body("Failed to load multi-model AI data: " + e.getMessage());
        }
    }

    /**
     * AI 예측 데이터 조회 API
     * @param modelName 모델명 (선택)
     * @param foldNumber Fold 번호 (선택)
     */
    @GetMapping("/predictions")
    public ResponseEntity<?> getPredictions(
        @RequestParam(required = false) String modelName,
        @RequestParam(required = false) Integer foldNumber) {

        try {
            log.info("데이터 조회 요청: modelName={}, foldNumber={}", modelName, foldNumber);

            List<HistoricalAiPrediction> predictions;

            if (modelName != null && foldNumber != null) {
                // 모델 + Fold 필터링
                log.info("모델+Fold 필터링: KRW-ETH, Fold {}, {}", foldNumber, modelName);
                predictions = aiPredictionRepository.findByMarketAndFoldNumberAndModelNameOrderByPredictionDateAsc(
                    "KRW-ETH", foldNumber, modelName);
            } else if (modelName != null) {
                // 모델만 필터링 (모든 Fold)
                log.info("모델 필터링: KRW-ETH, {} (전체 Fold)", modelName);
                predictions = aiPredictionRepository.findByMarketAndModelNameOrderByPredictionDateAsc(
                    "KRW-ETH", modelName);
            } else if (foldNumber != null) {
                // Fold만 필터링 (모든 모델)
                log.info("Fold 필터링: KRW-ETH, Fold {} (전체 모델)", foldNumber);
                predictions = aiPredictionRepository.findByMarketAndFoldNumberOrderByPredictionDateAsc(
                    "KRW-ETH", foldNumber);
            } else {
                // 전체 조회
                log.info("전체 조회 (전체 데이터)");
                predictions = aiPredictionRepository.findAll();
            }

            log.info("조회 결과: {}건 반환", predictions.size());
            return ResponseEntity.ok(predictions);
        } catch (Exception e) {
            log.error("데이터 조회 실패: modelName={}, foldNumber={}", modelName, foldNumber, e);
            return ResponseEntity.internalServerError().body("Failed to get predictions: " + e.getMessage());
        }
    }

    /**
     * 데이터 적재 상태 DTO
     */
    public record DataStatus(
        boolean ohlcvLoaded,
        boolean aiPredictionsLoaded,
        long ohlcvCount,
        long aiPredictionCount
    ) {}
}