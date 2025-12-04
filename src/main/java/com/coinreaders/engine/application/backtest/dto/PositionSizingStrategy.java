package com.coinreaders.engine.application.backtest.dto;

/**
 * 포지션 사이징 전략
 *
 * 각 전략은 Kelly Criterion을 기반으로 포지션 크기를 조정하는 다른 방법을 제공합니다.
 */
public enum PositionSizingStrategy {

    /**
     * Conservative Kelly (확률 조정 방식)
     *
     * 수식:
     * adjusted_proba = pred_proba_up × confidence + 0.5 × (1 - confidence)
     * position = Kelly(adjusted_proba)
     *
     * 원리:
     * - confidence가 낮으면 예측 확률을 중립(0.5)로 회귀
     * - Bayesian Shrinkage, Credibility Theory 기반
     * - 파라미터 튜닝 불필요
     *
     * 예시:
     * pred=0.8, conf=0.3, R=2.0
     * → adjusted = 0.8 × 0.3 + 0.5 × 0.7 = 0.59
     * → Kelly(0.59) = 38.5%
     */
    CONSERVATIVE_KELLY("Conservative Kelly", "확률 조정 방식 - Bayesian Shrinkage 기반"),

    /**
     * Estimation Risk Kelly (Bayesian Variance)
     *
     * 수식:
     * σ² = pred_proba × (1-pred_proba) / n_effective
     * n_effective = 1 + (confidence/0.5) × 99
     * adjustment = 1 - λ × σ²
     * position = Kelly(pred_proba) × adjustment
     *
     * 원리:
     * - confidence를 "effective sample size"로 해석
     * - Beta distribution의 variance 활용
     * - Parameter Uncertainty 고려
     *
     * 예시:
     * pred=0.8, conf=0.3, R=2.0, λ=2.0
     * → n_eff = 60.4
     * → σ² = 0.8 × 0.2 / 60.4 = 0.0026
     * → adjustment = 1 - 2.0 × 0.0026 = 0.995
     * → Kelly(0.8) × 0.995 = 69.6%
     */
    ESTIMATION_RISK_KELLY("Estimation Risk Kelly", "Bayesian Variance 기반 - 추정 불확실성 고려"),

    /**
     * Half Kelly
     *
     * 수식:
     * position = Kelly(pred_proba) × 0.5
     *
     * 원리:
     * - Pure Kelly의 50%만 사용
     * - 업계 표준 (Ed Thorp, 켈리 공식 창시자 추천)
     * - confidence 무시하고 일관되게 축소
     *
     * 예시:
     * pred=0.8, R=2.0
     * → Kelly(0.8) = 70%
     * → Half Kelly = 35%
     */
    HALF_KELLY("Half Kelly", "Pure Kelly의 50% - 업계 표준"),

    /**
     * Fixed 100% (비중 조절 없음)
     *
     * 수식:
     * position = 100% (자본 전체)
     *
     * 원리:
     * - 매번 보유한 전체 자본을 투자
     * - 켈리 공식 미사용
     * - 가장 공격적이지만 위험도 최대
     *
     * 예시:
     * 자본=10,000원
     * → 10,000원 전액 투자
     */
    FIXED_100_PERCENT("Fixed 100%", "매번 전액 투자 - 비중 조절 없음"),

    /**
     * Current (Kelly × Confidence) - 기존 방식
     *
     * 수식:
     * position = Kelly(pred_proba) × confidence
     *
     * 원리:
     * - Kelly 결과에 confidence를 직접 곱함
     * - 60~90% 포지션 축소로 매우 보수적
     * - 이론적 근거 약함 (중복 보정)
     *
     * 예시:
     * pred=0.8, conf=0.3, R=2.0
     * → Kelly(0.8) = 70%
     * → 70% × 0.3 = 21%
     */
    CURRENT_KELLY_TIMES_CONFIDENCE("Current (Kelly×Confidence)", "기존 방식 - 참고용");

    private final String displayName;
    private final String description;

    PositionSizingStrategy(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
