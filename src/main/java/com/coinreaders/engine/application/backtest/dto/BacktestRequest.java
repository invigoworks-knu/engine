package com.coinreaders.engine.application.backtest.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class BacktestRequest {

    private Integer foldNumber; // 1~8
    private BigDecimal initialCapital = new BigDecimal("10000"); // 기본값 10,000
    private BigDecimal confidenceThreshold = new BigDecimal("0.1"); // 기본값 0.1 (FIXED) 또는 10 (QUANTILE = 상위 90%)

    /**
     * 신뢰도 임계값 모드
     * FIXED: confidenceThreshold를 고정값으로 사용 (0~0.5)
     * QUANTILE: confidenceThreshold를 백분위수로 사용 (0~100, 예: 50 = 상위 50%)
     */
    private ThresholdMode thresholdMode = ThresholdMode.FIXED; // 기본값: 고정값 모드

    /**
     * 포지션 크기 비율 (0~100%)
     * null이면 Kelly Criterion 자동 계산 사용
     * 예: 50 = 자본의 50% 투자, 100 = 전액 투자
     */
    private BigDecimal positionSizePercent; // null = Kelly, 숫자 = 고정 비율

    public BacktestRequest(Integer foldNumber) {
        this.foldNumber = foldNumber;
    }

    public BacktestRequest(Integer foldNumber, BigDecimal initialCapital, BigDecimal confidenceThreshold) {
        this.foldNumber = foldNumber;
        this.initialCapital = initialCapital;
        this.confidenceThreshold = confidenceThreshold;
    }

    public BacktestRequest(Integer foldNumber, BigDecimal initialCapital, BigDecimal confidenceThreshold, BigDecimal positionSizePercent) {
        this.foldNumber = foldNumber;
        this.initialCapital = initialCapital;
        this.confidenceThreshold = confidenceThreshold;
        this.positionSizePercent = positionSizePercent;
        this.thresholdMode = ThresholdMode.FIXED; // 기본값
    }

    public BacktestRequest(Integer foldNumber, BigDecimal initialCapital, BigDecimal confidenceThreshold,
                           ThresholdMode thresholdMode, BigDecimal positionSizePercent) {
        this.foldNumber = foldNumber;
        this.initialCapital = initialCapital;
        this.confidenceThreshold = confidenceThreshold;
        this.thresholdMode = thresholdMode;
        this.positionSizePercent = positionSizePercent;
    }
}
