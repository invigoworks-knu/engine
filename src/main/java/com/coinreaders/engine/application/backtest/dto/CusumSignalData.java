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
 *
 * CSV 컬럼 매핑:
 * - signal_time: 신호 발생 시각 (트리거)
 * - strategy: 전략 ID (예: target_24h_Jackpot)
 * - model: 모델 ID (LGBM, XGB, ET)
 * - fold_id: 검증 차수
 * - primary_signal: 1차 신호 (CUSUM 기반)
 * - ml_prediction: AI 예측 (1=상승, 0=하락)
 * - final_action: 최종 명령 (BUY/PASS)
 * - confidence: AI 확신도 (0~1)
 * - threshold: 확신도 기준점
 * - cusum_selectivity_pct: 신호 희소성 (%)
 * - suggested_weight: 매수 비중 (Kelly Criterion)
 * - entry_price_ref: 참고 진입가
 * - take_profit_price: 익절가
 * - stop_loss_price: 손절가
 * - expiration_time: 타임 컷 시각
 * - actual_direction: 실제 방향 (정답)
 * - correct: 예측 정확 여부
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CusumSignalData {

    // ===== 1. 식별자 및 메타 정보 (Identity & Meta) =====
    private LocalDateTime signalTime;       // [트리거] 신호 발생 시각 (Primary Key 역할)
    private String strategy;                // [전략 ID] 예: target_24h_Jackpot
    private String model;                   // [모델 ID] LGBM, XGB, ET
    private Integer foldId;                 // [검증 차수] 학습/테스트 구간 번호

    // ===== 2. 매매 신호 및 필터링 (Signal & Filter) =====
    private Boolean primarySignal;          // [1차 신호] CUSUM 기반 매매 기회 포착 여부
    private Integer mlPrediction;           // [AI 예측] 1=상승, 0=하락/횡보
    private String finalAction;             // [최종 명령] BUY 또는 PASS
    private BigDecimal confidence;          // [확신도] AI 확신 정도 (0~1)
    private BigDecimal threshold;           // [기준점] 확신도 임계값
    private BigDecimal cusumSelectivityPct; // [희소성] 변동성 상위 N% (예: 20.1 = 상위 20%)

    // ===== 3. 실행 및 자금 관리 (Execution & Risk) =====
    private BigDecimal suggestedWeight;     // [매수 비중] 0~1 (Kelly Criterion 적용, 예: 0.25 = 25%)
    private BigDecimal entryPriceRef;       // [참고 진입가] 신호 발생 당시 가격
    private BigDecimal takeProfitPrice;     // [익절가] 매도 주문 가격
    private BigDecimal stopLossPrice;       // [손절가] 역지정가 주문 가격
    private LocalDateTime expirationTime;   // [타임 컷] TP/SL 미체결 시 강제 청산 시각

    // ===== 4. 사후 검증용 (Validation) =====
    private Integer actualDirection;        // [실제 방향] 1=상승, 0=하락 (정답지)
    private Boolean correct;                // [정답 여부] AI 예측이 맞았는지

    /**
     * 전략에서 보유 기간(시간) 추출
     * 예: "target_24h_Jackpot" → 24
     */
    public int getHoldingHours() {
        if (strategy == null) return 4; // 기본값

        // target_4h_, target_12h_, target_24h_, target_48h_ 패턴
        if (strategy.contains("_4h_") || strategy.contains("_4h")) return 4;
        if (strategy.contains("_12h_") || strategy.contains("_12h")) return 12;
        if (strategy.contains("_24h_") || strategy.contains("_24h")) return 24;
        if (strategy.contains("_48h_") || strategy.contains("_48h")) return 48;

        return 4; // 기본값
    }

    /**
     * 전략에서 모델명 추출
     * 예: "target_24h_Jackpot" → "Jackpot"
     */
    public String getStrategyType() {
        if (strategy == null) return "Unknown";

        // 마지막 _ 이후가 전략 타입
        int lastUnderscore = strategy.lastIndexOf('_');
        if (lastUnderscore > 0 && lastUnderscore < strategy.length() - 1) {
            return strategy.substring(lastUnderscore + 1);
        }
        return strategy;
    }

    /**
     * 전략 타입 추출 (시간 기반)
     * 예: "target_24h_Jackpot" → "24h"
     */
    public String getStrategyTimeframe() {
        if (strategy == null) return "4h";

        if (strategy.contains("_4h")) return "4h";
        if (strategy.contains("_12h")) return "12h";
        if (strategy.contains("_24h")) return "24h";
        if (strategy.contains("_48h")) return "48h";

        return "4h";
    }

    /**
     * BUY 신호인지 확인
     */
    public boolean isBuySignal() {
        return "BUY".equalsIgnoreCase(finalAction);
    }

    /**
     * 투자 비중 반환 (기본값: 0.8)
     * suggested_weight가 없으면 80% 기본값 사용
     */
    public BigDecimal getInvestmentRatio() {
        if (suggestedWeight != null && suggestedWeight.compareTo(BigDecimal.ZERO) > 0) {
            return suggestedWeight;
        }
        return new BigDecimal("0.8"); // 기본값 80%
    }

    /**
     * 익절 비율 계산 (%)
     */
    public BigDecimal getTakeProfitPct() {
        if (entryPriceRef == null || takeProfitPrice == null ||
            entryPriceRef.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return takeProfitPrice.subtract(entryPriceRef)
            .divide(entryPriceRef, 6, java.math.RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));
    }

    /**
     * 손절 비율 계산 (%)
     */
    public BigDecimal getStopLossPct() {
        if (entryPriceRef == null || stopLossPrice == null ||
            entryPriceRef.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return entryPriceRef.subtract(stopLossPrice)
            .divide(entryPriceRef, 6, java.math.RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));
    }

    /**
     * 진입가 반환 (호환성 유지)
     */
    public BigDecimal getEntryPrice() {
        return entryPriceRef;
    }

    /**
     * 익절가 반환 (호환성 유지)
     */
    public BigDecimal getTakeProfit() {
        return takeProfitPrice;
    }

    /**
     * 손절가 반환 (호환성 유지)
     */
    public BigDecimal getStopLoss() {
        return stopLossPrice;
    }

    /**
     * 만료시각 반환 (호환성 유지)
     */
    public LocalDateTime getExpiration() {
        return expirationTime;
    }
}
