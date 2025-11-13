package com.coinreaders.engine.application.backtest;

import com.coinreaders.engine.application.backtest.dto.CsvPredictionData;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 파일에서 예측 데이터를 로드하는 서비스
 * @deprecated DB 기반 데이터 로딩으로 대체되었습니다. {@link com.coinreaders.engine.application.AiPredictionDataService} 사용을 권장합니다.
 */
@Deprecated(since = "2025-01", forRemoval = true)
@Service
@Slf4j
public class CsvPredictionLoaderService {

    /**
     * Fold별 GRU 예측 CSV 파일 로드
     * @param foldNumber 1~8
     * @return 예측 데이터 리스트
     */
    public List<CsvPredictionData> loadGruPredictions(int foldNumber) {
        String fileName = String.format("data/fold%d_GRU_predictions.csv", foldNumber);
        return loadCsvFile(fileName);
    }

    /**
     * CSV 파일 읽기
     */
    private List<CsvPredictionData> loadCsvFile(String filePath) {
        log.info("CSV 파일 로드 시작: {}", filePath);

        List<CsvPredictionData> predictions = new ArrayList<>();
        ClassPathResource resource = new ClassPathResource(filePath);

        try (CSVReader reader = new CSVReader(
            new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String[] nextLine;
            reader.readNext(); // 헤더 건너뛰기

            while ((nextLine = reader.readNext()) != null) {
                try {
                    // CSV 구조:
                    // date,actual_direction,actual_return,pred_direction,
                    // pred_proba_up,pred_proba_down,max_proba,confidence,correct

                    CsvPredictionData data = CsvPredictionData.builder()
                        .date(LocalDate.parse(nextLine[0], DateTimeFormatter.ISO_LOCAL_DATE))
                        .actualDirection(Integer.parseInt(nextLine[1]))
                        .actualReturn(new BigDecimal(nextLine[2]))
                        .predDirection(Integer.parseInt(nextLine[3]))
                        .predProbaUp(new BigDecimal(nextLine[4]))
                        .predProbaDown(new BigDecimal(nextLine[5]))
                        .maxProba(new BigDecimal(nextLine[6]))
                        .confidence(new BigDecimal(nextLine[7]))
                        .correct(Integer.parseInt(nextLine[8]))
                        .build();

                    predictions.add(data);

                } catch (Exception e) {
                    log.warn("CSV 행 파싱 실패 (건너뜀): {}", String.join(",", nextLine), e);
                }
            }

            log.info("CSV 파일 로드 완료: {} ({}건)", filePath, predictions.size());

        } catch (IOException | CsvValidationException e) {
            log.error("CSV 파일 읽기 실패: {}", filePath, e);
            throw new RuntimeException("CSV 파일 처리 중 오류 발생: " + filePath, e);
        }

        return predictions;
    }
}
