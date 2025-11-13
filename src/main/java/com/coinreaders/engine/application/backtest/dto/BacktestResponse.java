package com.coinreaders.engine.application.backtest.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BacktestResponse {

    // 기본 정보
    private Integer foldNumber;
    private String regime;
    private LocalDate startDate;
    private LocalDate endDate;

    // Kelly 전략 결과
    private KellyStrategyResult kellyStrategy;

    // Buy & Hold 결과
    private BuyHoldResult buyHoldStrategy;

    // 비교 지표
    private BigDecimal alpha; // Kelly 수익률 - B&H 수익률
    private String winner; // "KELLY" or "BUY_AND_HOLD"

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class KellyStrategyResult {
        private BigDecimal initialCapital;
        private BigDecimal finalCapital;
        private BigDecimal totalReturnPct; // 총 수익률 (%)
        private Integer totalTrades;
        private Integer winTrades;
        private Integer lossTrades;
        private BigDecimal winRate; // 승률 (%)
        private BigDecimal avgWin; // 평균 수익 (%)
        private BigDecimal avgLoss; // 평균 손실 (%)
        private BigDecimal winLossRatio; // 손익비 (R)
        private BigDecimal kellyFraction; // 켈리 비율 (F)
        private BigDecimal maxDrawdown; // 최대 낙폭 (%)
        private BigDecimal sharpeRatio; // 샤프 비율 (선택)

        // 내부용: 연속 백테스팅 MDD 계산을 위한 자본 이력 (API 응답에는 포함되지 않음)
        @JsonIgnore
        private List<BigDecimal> capitalHistory;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BuyHoldResult {
        private BigDecimal initialCapital;
        private BigDecimal finalCapital;
        private BigDecimal totalReturnPct; // 총 수익률 (%)
        private BigDecimal entryPrice;
        private BigDecimal exitPrice;
        private BigDecimal maxDrawdown; // 최대 낙폭 (%)
    }
}
