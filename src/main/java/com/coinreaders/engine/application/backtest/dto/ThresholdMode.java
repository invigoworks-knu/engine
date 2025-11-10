package com.coinreaders.engine.application.backtest.dto;

/**
 * 신뢰도 임계값 필터링 모드
 */
public enum ThresholdMode {
    /**
     * 고정값 모드: 사용자가 지정한 값 그대로 사용
     * 예: 0.1 입력 → confidence >= 0.1
     */
    FIXED,

    /**
     * 백분위수 모드: 전체 데이터의 백분위수를 계산하여 임계값 결정
     * 예: 50 입력 → quantile(0.50) 계산 → confidence >= 계산된 값
     * 파이썬 코드와 동일한 방식
     */
    QUANTILE
}
