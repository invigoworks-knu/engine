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

    // ===== CUSUM 전용 필드 =====
    private String strategy;            // CUSUM 전략명 (예: target_24h_Jackpot)
    private String mlModel;             // CUSUM ML 모델 (예: LGBM, XGB)
    private String strategyTimeframe;   // 타임프레임 (4h, 12h, 24h, 48h)
    private String strategyType;        // 전략 타입 (Scalp, Balanced, Classic, Trend, Jackpot)
    private BigDecimal avgConfidence;   // 평균 AI 확신도
    private BigDecimal avgSelectivity;  // 평균 CUSUM 선별율 (%)
    private BigDecimal avgInvestmentRatio; // 평균 투자 비중 (Kelly)

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

        private LocalDate exitDate;             // 최종 청산일 (하위 호환)
        private LocalDateTime exitDateTime;     // 최종 청산 시각 (하위 호환)
        private BigDecimal exitPrice;           // 가중 평균 청산가 (하위 호환)

        private BigDecimal takeProfitPrice;
        private BigDecimal stopLossPrice;

        private BigDecimal positionSize;        // 초기 투자 금액
        private BigDecimal investmentRatio;     // 투자 비중 (0~1)

        private BigDecimal profit;              // 총 손익 (원)
        private BigDecimal returnPct;           // 총 수익률 (%)

        private String exitReason;              // 최종 청산 이유 (하위 호환)
        private BigDecimal holdingDays;         // 평균 보유 기간 (일)

        private BigDecimal predProbaUp;         // AI 예측 확률
        private BigDecimal confidence;          // 신뢰도

        private BigDecimal capitalAfter;        // 거래 후 자본

        // 분할 청산 이벤트 리스트 (비어있으면 단일 청산)
        private List<ExitEvent> exitEvents;

        // ===== CUSUM 전용 필드 =====
        private String strategy;                // CUSUM 전략명 (예: target_24h_Jackpot)
        private String mlModel;                 // CUSUM ML 모델 (예: LGBM)
        private BigDecimal cusumSelectivity;    // CUSUM 선별율 (%)
        private BigDecimal threshold;           // AI 확신도 임계값
        private Boolean isCorrect;              // 예측 정확 여부
    }

    /**
     * 청산 이벤트 (분할 청산 지원)
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ExitEvent {
        private LocalDateTime exitDateTime;
        private BigDecimal exitPrice;
        private BigDecimal exitRatio;           // 청산 비율 (0~1, 예: 0.3 = 30%)
        private BigDecimal exitAmount;          // 청산 금액 (원)
        private BigDecimal profit;              // 이 청산의 손익 (원)
        private BigDecimal returnPct;           // 이 청산의 수익률 (%)
        private String exitReason;              // PROFIT_LADDER, TIME_DECAY, TAKE_PROFIT, STOP_LOSS, TIMEOUT
        private String triggerCondition;        // 예: "Return >= 5%", "Day 6", "TP Hit"
    }
}
