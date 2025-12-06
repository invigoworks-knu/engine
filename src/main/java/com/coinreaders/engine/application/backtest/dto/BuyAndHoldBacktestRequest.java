package com.coinreaders.engine.application.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Buy & Hold 백테스팅 요청 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BuyAndHoldBacktestRequest {

    /**
     * Fold 번호 (1~8)
     */
    private Integer foldNumber;

    /**
     * 초기 자본 (기본값: 10,000원)
     */
    @Builder.Default
    private BigDecimal initialCapital = new BigDecimal("10000");
}
