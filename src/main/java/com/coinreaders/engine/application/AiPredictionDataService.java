package com.coinreaders.engine.application;

import com.coinreaders.engine.domain.entity.HistoricalAiPrediction;
import com.coinreaders.engine.domain.repository.HistoricalAiPredictionRepository;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiPredictionDataService {

    private final HistoricalAiPredictionRepository predictionRepository;

    /**
     * 리소스 폴더의 AI 예측 CSV 파일을 DB에 적재합니다.
     * @param modelVersion "GRU-f8" 등 DB에 저장될 모델명
     * @param filePath "data/GRU_fold_8_predictions.csv"
     */
    @Transactional
    public void loadAiPredictionsFromCsv(String modelVersion, String filePath) {
        log.info("AI 예측 데이터 적재 시작: {} (File: {})", modelVersion, filePath);

        // 1. 적재 전, 혹시 모를 중복 모델 데이터 삭제
        predictionRepository.deleteByAiModelVersion(modelVersion);
        log.info("기존 {} 모델 데이터를 삭제했습니다.", modelVersion);

        // 2. 리소스 경로에서 파일 찾기
        ClassPathResource resource = new ClassPathResource(filePath);

        // 3. CSVReader를 사용하여 파일 읽기 (try-with-resources)
        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String[] nextLine;
            reader.readNext(); // 4. 헤더 행(첫 번째 줄) 건너뛰기

            int count = 0;
            // 5. 데이터 행 반복 처리
            while ((nextLine = reader.readNext()) != null) {
                String dateStr = nextLine[0];
                int predDirectionValue = Integer.parseInt(nextLine[3]); // pred_direction 컬럼 사용
                String predDirection = predDirectionValue == 1 ? "UP" : "DOWN";

                // pred_direction에 따라 해당하는 확률값 사용
                BigDecimal probability = predDirectionValue == 1
                    ? new BigDecimal(nextLine[4])  // pred_proba_up
                    : new BigDecimal(nextLine[5]); // pred_proba_down

                LocalDateTime candleDateTimeKst = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atTime(9, 0, 0);

                HistoricalAiPrediction prediction = HistoricalAiPrediction.of(
                    "KRW-ETH",
                    candleDateTimeKst,
                    modelVersion,
                    predDirection,
                    probability
                );

                predictionRepository.save(prediction);
                count++;
            }
            log.info("AI 예측 데이터 적재 완료: {}. 총 {}건", modelVersion, count);

        } catch (IOException | CsvValidationException e) {
            log.error("CSV 파일 읽기 실패: {}", filePath, e);
            throw new RuntimeException("CSV 파일 처리 중 오류 발생", e);
        }
    }
}