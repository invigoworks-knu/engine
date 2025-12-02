package com.coinreaders.engine.application.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Take Profit / Stop Loss 백테스팅 응답 DTO
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TakeProfitStopLossBacktestResponse {

    private String modelName;
    private Integer foldNumber;
    private String regime; // BULL/BEAR/SIDEWAYS
    private LocalDate startDate;
    private LocalDate endDate;

    // 전략 결과
    private BigDecimal initialCapital;
    private BigDecimal finalCapital;
    private BigDecimal totalReturnPct;

    // 거래 통계
    private Integer totalTrades;
    private Integer takeProfitExits;    // 익절 횟수
    private Integer stopLossExits;      // 손절 횟수
    private Integer timeoutExits;       // 8일 만료 횟수
    private BigDecimal winRate;         // 승률
    private BigDecimal avgHoldingDays;  // 평균 보유 기간

    // 리스크 지표
    private BigDecimal maxDrawdown;
    private BigDecimal sharpeRatio;
    private BigDecimal avgWin;
    private BigDecimal avgLoss;
    private BigDecimal winLossRatio;

    // 거래 상세 내역
    private List<TradeDetail> tradeHistory;

    /**
     * 개별 거래 상세 정보
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TradeDetail {
        private Integer tradeNumber;
        private LocalDate entryDate;
        private LocalDateTime entryDateTime;
        private BigDecimal entryPrice;

        private LocalDate exitDate;
        private LocalDateTime exitDateTime;
        private BigDecimal exitPrice;

        private BigDecimal takeProfitPrice;
        private BigDecimal stopLossPrice;

        private BigDecimal positionSize;        // 투자 금액
        private BigDecimal investmentRatio;     // 투자 비중 (0~1)

        private BigDecimal profit;              // 손익 (원)
        private BigDecimal returnPct;           // 수익률 (%)

        private String exitReason;              // TAKE_PROFIT, STOP_LOSS, TIMEOUT
        private BigDecimal holdingDays;         // 보유 기간 (일)

        private BigDecimal predProbaUp;         // AI 예측 확률
        private BigDecimal confidence;          // 신뢰도

        private BigDecimal capitalAfter;        // 거래 후 자본
    }
}
