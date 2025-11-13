package com.coinreaders.engine.application.backtest.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Fold 1~7 연속 백테스팅 응답
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SequentialBacktestResponse {

    // 전체 요약
    private BigDecimal initialCapital;
    private BigDecimal finalCapital;
    private BigDecimal totalReturnPct;
    private Integer startFold;
    private Integer endFold;

    // Kelly vs Buy & Hold 비교
    private StrategyComparison kellyComparison;
    private StrategyComparison buyHoldComparison;

    // Fold별 상세 결과
    private List<FoldResult> foldResults;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StrategyComparison {
        private BigDecimal initialCapital;
        private BigDecimal finalCapital;
        private BigDecimal totalReturnPct;
        private Integer totalTrades;
        private BigDecimal totalAlpha; // vs Buy & Hold

        // 추가 통계
        private Integer totalWins;
        private Integer totalLosses;
        private BigDecimal overallWinRate;
        private BigDecimal overallMaxDrawdown;
        private BigDecimal overallSharpeRatio;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FoldResult {
        private Integer foldNumber;
        private String regime;
        private String dateRange;

        // Kelly 전략
        private BigDecimal kellyInitialCapital;
        private BigDecimal kellyFinalCapital;
        private BigDecimal kellyReturnPct;
        private Integer kellyTrades; // 거래 횟수
        private BigDecimal kellyMdd; // 최대 낙폭 (%)

        // Buy & Hold 전략
        private BigDecimal buyHoldInitialCapital;
        private BigDecimal buyHoldFinalCapital;
        private BigDecimal buyHoldReturnPct;
        private BigDecimal buyHoldMdd; // 최대 낙폭 (%)

        // 비교
        private BigDecimal alpha;
        private String winner;
    }
}
