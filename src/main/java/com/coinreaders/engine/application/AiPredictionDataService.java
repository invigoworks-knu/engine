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
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiPredictionDataService {

    private final HistoricalAiPredictionRepository predictionRepository;
    private static final String MARKET = "KRW-ETH";

    /**
     * Fold별 GRU 예측 CSV 파일을 DB에 적재합니다.
     * @param foldNumber 1~8
     */
    @Transactional
    public void loadAiPredictionsFromCsv(int foldNumber) {
        String filePath = String.format("data/fold%d_GRU_predictions.csv", foldNumber);
        log.info("AI 예측 데이터 적재 시작: Fold {} (File: {})", foldNumber, filePath);

        // 1. 적재 전, 해당 fold 데이터 삭제
        predictionRepository.deleteByMarketAndFoldNumber(MARKET, foldNumber);
        log.info("기존 Fold {} 데이터를 삭제했습니다.", foldNumber);

        // 2. 리소스 경로에서 파일 찾기
        ClassPathResource resource = new ClassPathResource(filePath);

        // 3. CSVReader를 사용하여 파일 읽기 (try-with-resources)
        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String[] nextLine;
            reader.readNext(); // 4. 헤더 행(첫 번째 줄) 건너뛰기

            int count = 0;
            // 5. 데이터 행 반복 처리
            // CSV 구조: date, actual_direction, actual_return, pred_direction,
            //           pred_proba_up, pred_proba_down, max_proba, confidence, correct
            while ((nextLine = reader.readNext()) != null) {
                // CSV 컬럼 개수 검증
                if (nextLine.length < 9) {
                    log.warn("잘못된 CSV 행 형식 (컬럼 수 부족): {}", String.join(",", nextLine));
                    continue;
                }

                try {
                    LocalDate predictionDate = LocalDate.parse(nextLine[0], DateTimeFormatter.ISO_LOCAL_DATE);
                    Integer actualDirection = Integer.parseInt(nextLine[1].trim());
                    BigDecimal actualReturn = new BigDecimal(nextLine[2].trim());
                    Integer predDirection = Integer.parseInt(nextLine[3].trim());
                    BigDecimal predProbaUp = new BigDecimal(nextLine[4].trim());
                    BigDecimal predProbaDown = new BigDecimal(nextLine[5].trim());
                    BigDecimal maxProba = new BigDecimal(nextLine[6].trim());
                    BigDecimal confidence = new BigDecimal(nextLine[7].trim());
                    Integer correct = Integer.parseInt(nextLine[8].trim());

                    HistoricalAiPrediction prediction = HistoricalAiPrediction.of(
                        MARKET,
                        predictionDate,
                        foldNumber,
                        actualDirection,
                        actualReturn,
                        predDirection,
                        predProbaUp,
                        predProbaDown,
                        maxProba,
                        confidence,
                        correct
                    );

                    predictionRepository.save(prediction);
                    count++;

                } catch (Exception e) {
                    log.warn("CSV 행 파싱 실패 (건너뜀): {}", String.join(",", nextLine), e);
                }
            }
            log.info("AI 예측 데이터 적재 완료: Fold {}. 총 {}건", foldNumber, count);

        } catch (IOException | CsvValidationException e) {
            log.error("CSV 파일 읽기 실패: {}", filePath, e);
            throw new RuntimeException("CSV 파일 처리 중 오류 발생", e);
        }
    }
}