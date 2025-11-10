package com.coinreaders.engine.application.backtest.dto;

/**
 * 신뢰도 필터링에 사용할 컬럼 선택
 */
public enum ConfidenceColumn {
    /**
     * confidence 컬럼 사용 (모델의 확신도)
     * - 범위: 0~0.5 정도
     * - 파이썬 백테스팅 코드에서 사용한 방식
     */
    CONFIDENCE,

    /**
     * pred_proba_up 컬럼 사용 (상승 확률)
     * - 범위: 0~1 (주로 0.5 근처)
     * - 초기 Java 백테스팅 코드에서 사용한 방식
     */
    PRED_PROBA_UP
}
