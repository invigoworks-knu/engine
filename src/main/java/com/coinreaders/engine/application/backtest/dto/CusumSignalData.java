package com.coinreaders.engine.application.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CUSUM 필터 기반 신호 데이터 구조
 * (backend_signals_master.csv)
 *
 * 새로운 ML 파이프라인:
 * 1. CUSUM Filter: 노이즈 제거, 유의미한 추세 발생 시점만 선별
 * 2. Triple Barrier Method: 익절/손절/시간제한으로 라벨링
 * 3. AI 검증: 전략의 성공 가능성 판별 (Buy/Pass)
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CusumSignalData {

    private Integer foldId;                 // Fold 번호 (1~8)
    private LocalDateTime signalTime;       // CUSUM 신호 발생 시각
    private String strategy;                // 전략명 (예: target_4h_LGBM, target_24h_XGB)
    private String model;                   // 모델명 (LGBM, XGB, CAT, ET)

    private Boolean primarySignal;          // Primary 신호 여부
    private Integer mlPredict;              // ML 예측값 (0=하락, 1=상승)
    private String finalAction;             // 최종 행동 (PASS, BUY)
    private Integer suggested;              // AI 추천값 (0 또는 1)

    private BigDecimal entryPrice;          // 진입가
    private BigDecimal takeProfit;          // 익절가
    private BigDecimal stopLoss;            // 손절가
    private LocalDateTime expiration;       // 만료 시각

    private BigDecimal confidence;          // 신뢰도 (0~1)
    private BigDecimal threshold;           // 임계값 (예: 0.55)

    private Integer actualDirection;        // 실제 방향 (0=하락, 1=상승)
    private Boolean correct;                // 예측 정확도 (TRUE/FALSE)
    private BigDecimal cusumSel;            // CUSUM 선택 값

    /**
     * 전략에서 보유 기간(시간) 추출
     * 예: "target_4h_LGBM" → 4, "target_24h_XGB" → 24
     */
    public int getHoldingHours() {
        if (strategy == null) return 4; // 기본값

        // target_4h_, target_12h_, target_24h_, target_48h_ 패턴
        if (strategy.contains("_4h_")) return 4;
        if (strategy.contains("_12h_")) return 12;
        if (strategy.contains("_24h_")) return 24;
        if (strategy.contains("_48h_")) return 48;

        return 4; // 기본값
    }

    /**
     * 전략에서 모델명 추출
     * 예: "target_4h_LGBM" → "LGBM"
     */
    public String getStrategyModel() {
        if (strategy == null) return model;

        // 마지막 _ 이후가 모델명
        int lastUnderscore = strategy.lastIndexOf('_');
        if (lastUnderscore > 0 && lastUnderscore < strategy.length() - 1) {
            return strategy.substring(lastUnderscore + 1);
        }
        return model;
    }

    /**
     * 전략 타입 추출 (시간 기반)
     * 예: "target_4h_LGBM" → "4h"
     */
    public String getStrategyTimeframe() {
        if (strategy == null) return "4h";

        if (strategy.contains("_4h_")) return "4h";
        if (strategy.contains("_12h_")) return "12h";
        if (strategy.contains("_24h_")) return "24h";
        if (strategy.contains("_48h_")) return "48h";

        return "4h";
    }

    /**
     * BUY 신호인지 확인
     */
    public boolean isBuySignal() {
        return "BUY".equalsIgnoreCase(finalAction);
    }

    /**
     * 익절 비율 계산 (%)
     */
    public BigDecimal getTakeProfitPct() {
        if (entryPrice == null || takeProfit == null || entryPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return takeProfit.subtract(entryPrice)
            .divide(entryPrice, 6, java.math.RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));
    }

    /**
     * 손절 비율 계산 (%)
     */
    public BigDecimal getStopLossPct() {
        if (entryPrice == null || stopLoss == null || entryPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return entryPrice.subtract(stopLoss)
            .divide(entryPrice, 6, java.math.RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));
    }
}
