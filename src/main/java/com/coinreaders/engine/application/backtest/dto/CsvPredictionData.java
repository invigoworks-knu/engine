package com.coinreaders.engine.application.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CSV 파일의 예측 데이터 구조
 * (fold1_GRU_predictions.csv 등)
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CsvPredictionData {

    private LocalDate date;
    private Integer actualDirection;      // 0 or 1
    private BigDecimal actualReturn;      // 실제 수익률 (소수)
    private Integer predDirection;        // 0 or 1
    private BigDecimal predProbaUp;       // 상승 확률
    private BigDecimal predProbaDown;     // 하락 확률
    private BigDecimal maxProba;          // 최대 확률
    private BigDecimal confidence;        // 신뢰도
    private Integer correct;              // 0 or 1 (예측 정확도)
}
