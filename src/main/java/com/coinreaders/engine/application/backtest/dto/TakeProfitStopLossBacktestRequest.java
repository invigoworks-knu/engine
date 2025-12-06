package com.coinreaders.engine.application.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Take Profit / Stop Loss 백테스팅 요청 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TakeProfitStopLossBacktestRequest {

    /**
     * Fold 번호 (1~8)
     */
    private Integer foldNumber;

    /**
     * AI 모델명
     * "GRU", "LSTM", "BiLSTM", "XGBoost", "LightGBM", "CatBoost",
     * "RandomForest", "AdaBoost", "GradientBoosting", "HistGradientBoosting",
     * "LogisticRegression", "StackingEnsemble"
     */
    private String modelName;

    /**
     * 초기 자본 (기본값: 10,000원)
     */
    @Builder.Default
    private BigDecimal initialCapital = new BigDecimal("10000");

    /**
     * pred_proba_up 진입 임계값 (기본값: 0.6)
     * 0.6 이상일 때만 진입
     */
    @Builder.Default
    private BigDecimal predProbaThreshold = new BigDecimal("0.6");

    /**
     * 보유 기간 (기본값: 8일)
     * 8일 동안 TP/SL 추적
     */
    @Builder.Default
    private Integer holdingPeriodDays = 8;

    /**
     * 포지션 사이징 전략 (기본값: CONSERVATIVE_KELLY)
     */
    @Builder.Default
    private PositionSizingStrategy positionSizingStrategy = PositionSizingStrategy.CONSERVATIVE_KELLY;
}
