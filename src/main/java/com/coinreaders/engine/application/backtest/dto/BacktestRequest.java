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
    private BigDecimal confidenceThreshold = new BigDecimal("0.5"); // 기본값 50%

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
    }
}
