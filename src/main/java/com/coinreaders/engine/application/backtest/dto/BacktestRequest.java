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

    public BacktestRequest(Integer foldNumber) {
        this.foldNumber = foldNumber;
    }

    public BacktestRequest(Integer foldNumber, BigDecimal initialCapital, BigDecimal confidenceThreshold) {
        this.foldNumber = foldNumber;
        this.initialCapital = initialCapital;
        this.confidenceThreshold = confidenceThreshold;
    }
}
