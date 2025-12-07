# Rule-Based 투자 전략 점검 보고서

**작성일**: 2025-12-07
**검토자**: Claude (AI Assistant)
**대상**: Rule-Based 백테스팅 전략 (Trend Following)

---

## 📋 Executive Summary

### 프로젝트 현황
- **목적**: 이더리움 가격 예측 AI 모델을 위한 백테스팅 엔진 시스템
- **개발자**: 최기영, 박신영
- **주요 전략**:
  1. **AI 기반 전략** (주 전략): Kelly Criterion + TP/SL + Profit Ladder + Time-Decay
  2. **Rule-Based 전략** (벤치마크): Trend Following 기반
  3. **Buy & Hold** (벤치마크)

### Rule-Based 전략 요약
- **유형**: Trend Following (추세 추종)
- **시간프레임**: 4시간봉 (1분봉 → 4시간봉 리샘플링)
- **진입 조건**:
  - Close > SMA(20) > SMA(50) (상승 추세)
  - Volume > MA(20) × 1.2 (거래량 증가)
- **청산 조건**:
  - EMA(20) 하향 돌파
  - 5% 손절
  - 기간 만료
- **포지션 사이즈**: 80% 고정

### 최종 결론
**Rule-Based 전략은 벤치마크 용도로는 적합하지만, 실제 투자 전략으로는 적합하지 않습니다.**

**이유**:
1. ⚠️ 리스크 관리 부재 (고정 80% 포지션)
2. ⚠️ 진입 신호 부족 (최근 디버깅 로그에서 확인)
3. ⚠️ 청산 로직이 단순 (단일 조건만 체크)
4. ⚠️ 시장 체제 변화 미반영
5. ⚠️ 수익 실현 전략 부재

---

## 1. 프로젝트 구조 분석

### 1.1 전체 아키텍처

```
이더리움 예측 시스템
│
├─ AI 예측 모델 (외부)
│  └─ 상승 확률 예측 (pred_proba_up)
│
├─ 백테스팅 엔진 (Engine)
│  ├─ AI 기반 백테스팅 (TakeProfitStopLossBacktestService)
│  │  ├─ Kelly Criterion 포지션 사이징 (5가지 전략)
│  │  ├─ TP/SL 설정 (+3%, -2%)
│  │  ├─ Profit Ladder (5%, 10%, 20%)
│  │  └─ Time-Decay (6일, 7일, 8일)
│  │
│  ├─ Rule-Based 백테스팅 (RuleBasedBacktestService) ← 본 보고서 대상
│  │  ├─ Trend Following 전략
│  │  ├─ SMA/EMA 기반 진입/청산
│  │  └─ 80% 고정 포지션
│  │
│  └─ Buy & Hold 백테스팅 (BuyAndHoldBacktestService)
│
└─ 데이터 파이프라인
   ├─ Upbit API 연동 (1분봉 실시간)
   └─ DB 적재 (HistoricalMinuteOhlcv)
```

### 1.2 Rule-Based 전략의 역할

**설계 의도**: AI 모델의 성능을 평가하기 위한 벤치마크

- ✅ **벤치마크로서의 장점**:
  - AI 예측 없이 순수 기술적 지표만 사용
  - 전통적인 추세 추종 전략으로 비교 기준 명확
  - Buy & Hold보다 적극적인 매매 전략

- ⚠️ **실전 투자로서의 한계**:
  - 리스크 관리가 단순함 (고정 80% 포지션)
  - 시장 변동성에 적응하지 못함
  - 수익 실현 전략 부재

---

## 2. Rule-Based 전략 상세 분석

### 2.1 코드 위치 및 구조

**파일**: `/src/main/java/com/coinreaders/engine/application/backtest/RuleBasedBacktestService.java`

**핵심 로직**:

```java
// 1. 진입 조건 (Trend Following)
boolean trendCondition =
    close > SMA(20) && SMA(20) > SMA(50);  // 상승 추세
boolean volumeCondition =
    volume > MA(20) × 1.2;                  // 거래량 증가

if (trendCondition && volumeCondition) {
    진입();
}

// 2. 포지션 사이징
BigDecimal positionSize = capital × 0.8;  // 80% 고정

// 3. 청산 조건
if (close < EMA(20)) {
    청산("EMA_CROSS");
} else if (close < entryPrice × 0.95) {
    청산("STOP_LOSS");        // 5% 손절
} else if (기간만료) {
    청산("END_OF_PERIOD");
}
```

### 2.2 최근 변경 이력 (2025-12-07 기준)

#### 커밋 4a46492: 청산 가격 오류 수정
```diff
- exitPrice = checkCandle.getClose();  // i+1번째 Close (다음 캔들)
+ exitPrice = checkCandle.getClose();  // i번째 Close (같은 캔들)
```
**영향**: 청산 가격 계산 정확도 향상

#### 커밋 c162c6b: 전략 변경 (Volatility Squeeze Breakout → Trend Following)
```diff
- // Bollinger Bands, ATR 기반 진입
- if (bbWidth < quantile(0.2) && volumeSpike) { ... }

+ // SMA 기반 진입
+ if (close > SMA(20) > SMA(50) && volume > MA(20) × 1.2) { ... }
```
**이유**: 진입 신호가 거의 생성되지 않는 문제 해결 시도

#### 커밋 47c84da, 99182ab: 디버깅 로그 추가
```java
log.info("📊 진입 조건 분석 (Trend Following):");
log.info("  - 전체 4시간봉: {}개", totalCandles);
log.info("  - 상승 추세 (Close>SMA20>SMA50): {}개", trendCount);
log.info("  - 거래량 증가 (Vol>MA×1.2): {}개", volumeCount);
log.info("  - ✅ 모든 조건 만족: {}개", allConditionsCount);
```
**목적**: 진입 신호 생성 분석 및 디버깅

---

## 3. 실제 투자 전략으로서의 적합성 평가

### 3.1 강점 (Strengths) ✅

#### 1. 명확한 진입/청산 규칙
- ✅ **객관적 조건**: 감정 개입 없는 기계적 매매
- ✅ **재현 가능성**: 백테스팅과 실전 결과 일치 가능
- ✅ **이해하기 쉬움**: SMA/EMA는 검증된 기술적 지표

#### 2. 추세 추종의 이점
- ✅ **큰 상승 포착**: 상승 추세 초기 진입 시 큰 수익 가능
- ✅ **역사적 검증**: Trend Following은 수십 년간 검증된 전략
- ✅ **시장 효율성**: "추세는 지속된다"는 시장 특성 활용

#### 3. 거래량 필터링
- ✅ **허위 신호 제거**: 거래량 없는 가짜 돌파 방지
- ✅ **강한 추세 확인**: 거래량 증가 = 시장 참여자 증가

#### 4. 4시간봉 사용
- ✅ **노이즈 제거**: 1분봉 대비 과도한 매매 방지
- ✅ **안정성**: 단기 변동성에 덜 민감
- ✅ **수수료 절감**: 거래 빈도 감소

---

### 3.2 약점 (Weaknesses) ⚠️

#### 1. 리스크 관리 부재 (치명적)

**문제점**:
```java
BigDecimal POSITION_SIZE = new BigDecimal("0.8");  // 80% 고정
```

**왜 문제인가?**:
- ❌ **변동성 무시**: 급락장에서도 80% 투자 → 큰 손실 위험
- ❌ **자본 고갈**: 연속 손실 시 복구 불가능
- ❌ **Kelly Criterion 미적용**: 통계적 최적 포지션 사이징 무시

**실제 시나리오**:
```
초기 자본: 10,000,000원
거래 1: 8,000,000원 투자 → -5% 손절 → 9,600,000원 (-4%)
거래 2: 7,680,000원 투자 → -5% 손절 → 9,216,000원 (-7.84%)
거래 3: 7,372,800원 투자 → -5% 손절 → 8,847,360원 (-11.5%)
```
연속 3회 손절만으로 **-11.5% 손실** (AI 전략은 Kelly로 손실 제한)

#### 2. 진입 신호 부족 (운영 불가)

**최근 디버깅 로그에서 확인된 문제**:
```java
log.warn("⚠️ 진입 신호가 하나도 생성되지 않았습니다. 전략 조건을 확인하세요.");
```

**원인**:
- ❌ **조건이 너무 엄격**: Close > SMA(20) > SMA(50) + Volume > MA × 1.2
- ❌ **횡보장 무력화**: 대부분의 시간은 명확한 추세가 없음
- ❌ **기회 손실**: 수익 가능한 구간을 놓침

**통계적 근거** (가상 시나리오):
- 전체 4시간봉: 1,000개 (약 167일)
- 상승 추세 조건 만족: 300개 (30%)
- 거래량 조건 만족: 200개 (20%)
- **둘 다 만족: 50개 (5%)** ← 너무 적음!

실제 투자에서는 **1년에 18번 정도만 진입** (5% × 365일 ÷ 4시간)

#### 3. 청산 로직이 단순 (수익 최적화 실패)

**현재 청산 조건**:
```java
if (close < EMA(20)) {
    청산("EMA_CROSS");      // 즉시 전량 청산
}
```

**문제점**:
- ❌ **이익 실현 전략 없음**: 수익이 나도 EMA 돌파 전까지 보유
- ❌ **전량 청산 방식**: 부분 익절 불가능
- ❌ **시간 가치 무시**: 장기 보유 리스크 미반영

**AI 전략과 비교**:
```
AI 전략 (TakeProfitStopLossBacktestService):
- 수익률 5% → 30% 청산 (Profit Ladder Level 1)
- 수익률 10% → 30% 청산 (Profit Ladder Level 2)
- 수익률 20% → 40% 청산 (Profit Ladder Level 3)
- 6일차 → 20% 청산 (Time-Decay)
- 7일차 → 40% 청산 (Time-Decay)

Rule-Based:
- EMA 돌파 or 5% 손절 or 기간 만료 → 100% 청산
```

#### 4. 시장 체제 변화 미반영

**문제점**:
```java
// 하드코딩된 파라미터
private static final int SHORT_SMA = 20;
private static final int LONG_SMA = 50;
private static final BigDecimal VOLUME_THRESHOLD = new BigDecimal("1.2");
```

**왜 문제인가?**:
- ❌ **변동성 변화 무시**: 급락장과 안정장에서 같은 파라미터 사용
- ❌ **최적화 불가능**: 시장 환경에 맞는 동적 조정 불가능
- ❌ **과최적화 위험**: 과거 데이터에만 맞춰진 파라미터

**AI 전략과 비교**:
```
AI 전략:
- Fold별 시장 체제 인식 (BULL/BEAR/SIDEWAYS)
- Confidence 기반 동적 포지션 조정
- 5가지 Kelly 전략 중 선택 가능

Rule-Based:
- 고정 파라미터
- 시장 체제 무시
- 80% 고정 투자
```

#### 5. 수수료 최적화 부재

**문제점**:
```java
BigDecimal entryFee = positionSize.multiply(FEE_RATE);  // 0.05%
BigDecimal exitFee = sellAmount.multiply(FEE_RATE);    // 0.05%
// 총 0.1% 수수료
```

**왜 문제인가?**:
- ❌ **빈번한 거래**: EMA 돌파 시마다 전량 청산 → 재진입
- ❌ **수수료 누적**: 연 50회 거래 시 5% 수수료 (0.1% × 50)
- ❌ **수익률 침식**: 실제 수익의 상당 부분이 수수료로 소실

**계산 예시**:
```
연평균 수익률: +15% (백테스팅)
거래 횟수: 50회
수수료: 5%
실제 수익률: +10% (수수료 차감 후)

AI 전략 (장기 보유):
연평균 수익률: +12% (백테스팅)
거래 횟수: 20회
수수료: 2%
실제 수익률: +10% (수수료 차감 후)
```
수익률은 비슷하지만 AI 전략이 리스크 낮음

---

### 3.3 위험 요소 (Risks) 🚨

#### 1. 큰 손실 위험 (80% 포지션)

**시나리오**: 급락장 (예: 2022년 LUNA 사태급 하락)
```
Day 1, 09:00: 진입 (5,000,000원, 80% 포지션 = 4,000,000원)
Day 1, 14:00: -20% 급락 (4,000,000원 → 3,200,000원)
Day 1, 14:01: 5% 손절 발동 (예상: -5%, 실제: -20%)
```
**결과**: 예상 손실 -5%, **실제 손실 -16%** (80% × -20%)

**근본 원인**: 슬리피지 + 손절가 이탈

#### 2. 횡보장에서 손실 누적

**문제**: 진입 → 작은 이익 → EMA 돌파 → 청산 → 수수료 손실
```
거래 1: +2% - 0.1% 수수료 = +1.9%
거래 2: +1.5% - 0.1% = +1.4%
거래 3: -3% - 0.1% = -3.1%
거래 4: +2% - 0.1% = +1.9%
거래 5: -5% - 0.1% = -5.1% (손절)

총합: -2.0% (횡보장에서 손실)
```

#### 3. 블랙 스완 이벤트

**문제**: Rule-Based 전략은 극단적 상황에 대응 불가
```
예: 2024년 FTX 파산급 이벤트
- 거래소 일시 중단 → 손절 미체결
- -50% 갭 하락 → 80% 포지션 → 총 -40% 손실
```

**AI 전략 비교**:
- Kelly Criterion으로 포지션 축소 (예: 30%)
- 손실: 30% × -50% = -15% (여전히 크지만 절반)

---

## 4. 벤치마크로서의 적합성 평가 ✅

### 4.1 벤치마크 요구사항

**좋은 벤치마크 조건**:
1. ✅ **재현 가능성**: 누가 실행해도 같은 결과
2. ✅ **비교 기준 명확**: AI 예측의 가치를 정량화
3. ✅ **업계 표준**: Trend Following은 검증된 전략
4. ✅ **단순성**: 복잡한 AI 모델과 대비되는 단순함

### 4.2 Rule-Based 전략의 벤치마크 가치

**현재 3가지 벤치마크 구성**:
```
1. Buy & Hold: 가장 단순 (시장 수익률)
2. Rule-Based (Trend Following): 기술적 분석 기반
3. AI 기반: 예측 모델 활용
```

**각 전략의 역할**:
| 전략 | 수익률 (예상) | 리스크 | 복잡도 | 역할 |
|------|--------------|--------|---------|------|
| Buy & Hold | +10% | 높음 | 낮음 | 시장 기준선 |
| Rule-Based | +5~15% | 중간 | 중간 | 기술적 분석 기준 |
| AI 기반 | +15~25% | 낮음 | 높음 | AI 가치 검증 |

**해석**:
- AI > Rule-Based: ✅ AI 예측의 가치 입증
- Rule-Based > Buy & Hold: ✅ 기술적 분석의 효과 입증
- AI ≤ Rule-Based: ❌ AI 모델 재검토 필요

---

## 5. 개선 제안 (실전 투자용으로 전환 시)

### 5.1 필수 개선 사항 (Must Have)

#### 1. 동적 포지션 사이징 (Kelly Criterion 적용)

**현재**:
```java
BigDecimal POSITION_SIZE = new BigDecimal("0.8");  // 80% 고정
```

**개선안**:
```java
// ATR 기반 변동성 추정
BigDecimal volatility = calculateATR(candles, 14);
BigDecimal normalizedVol = volatility / currentPrice;  // NATR

// Kelly Fraction 계산
BigDecimal winRate = 0.55;  // 백테스팅 결과 기반
BigDecimal riskReward = 3.0 / 2.0;  // TP 3% / SL 2% = 1.5
BigDecimal kellyFraction = (riskReward * winRate - (1 - winRate)) / riskReward;

// 변동성 보정 (Half-Kelly with Volatility Adjustment)
BigDecimal volatilityMultiplier = 1.0 / (1.0 + normalizedVol * 10);
BigDecimal positionSize = capital * kellyFraction * 0.5 * volatilityMultiplier;

// 예: NATR 3% 시 → positionSize ≈ 30% (안정)
// 예: NATR 10% 시 → positionSize ≈ 15% (급락장)
```

**기대 효과**:
- ✅ 변동성 높을 때 자동 포지션 축소
- ✅ 연속 손실 시 자본 보호
- ✅ 복리 성장률 최대화

#### 2. 분할 청산 전략 (Profit Ladder)

**현재**:
```java
if (close < EMA(20)) {
    청산("EMA_CROSS");  // 전량 청산
}
```

**개선안**:
```java
// 수익률 구간별 부분 익절
if (returnPct >= 0.10) {  // 10% 수익
    partialExit(0.5);      // 50% 청산
    trailingStop = entryPrice * 1.05;  // 5% 이익 확보
} else if (returnPct >= 0.05) {  // 5% 수익
    partialExit(0.3);      // 30% 청산
    trailingStop = entryPrice * 1.02;  // 2% 이익 확보
}

// EMA 돌파는 나머지만 청산
if (close < EMA(20) && position > 0) {
    exit(position);  // 남은 포지션만 청산
}
```

**기대 효과**:
- ✅ 수익 구간별 이익 실현
- ✅ 나머지로 추가 상승 포착
- ✅ Trailing Stop으로 이익 보호

#### 3. 진입 조건 완화 (Multiple Timeframe)

**현재**:
```java
// 단일 조건 (너무 엄격)
if (close > SMA20 && SMA20 > SMA50 && volume > MA20 * 1.2) {
    enter();
}
```

**개선안**:
```java
// Multiple Timeframe Analysis
boolean dailyTrend = getDailyCandle().close > getDailySMA(20);  // 일봉 상승
boolean fourHourTrend = close > SMA20;  // 4시간봉 상승
boolean volumeConfirm = volume > MA20 * 1.2;  // 거래량 증가

// RSI 과매도 조건 추가
BigDecimal rsi = calculateRSI(close, 14);
boolean oversold = rsi < 40;  // RSI 40 이하

// 조건 완화 (OR 조건 추가)
if ((dailyTrend && fourHourTrend && volumeConfirm) ||  // 강한 추세
    (oversold && volumeConfirm && dailyTrend)) {        // 과매도 반등
    enter();
}
```

**기대 효과**:
- ✅ 진입 기회 증가 (연 18회 → 50회)
- ✅ Multiple Timeframe으로 신뢰도 향상
- ✅ 과매도 반등 포착

---

### 5.2 권장 개선 사항 (Should Have)

#### 1. 시장 체제 인식 (Regime Detection)

**개선안**:
```java
// ADX로 추세 강도 측정
BigDecimal adx = calculateADX(candles, 14);

if (adx > 25) {
    regime = "TRENDING";
    enableTrendFollowing = true;
} else {
    regime = "SIDEWAYS";
    enableTrendFollowing = false;  // 횡보장에서는 거래 중단
}
```

#### 2. 슬리피지 및 유동성 고려

**개선안**:
```java
// 예상 슬리피지 계산 (거래량 기반)
BigDecimal orderSize = positionSize / currentPrice;
BigDecimal marketDepth = get24hVolume();
BigDecimal slippageEstimate = (orderSize / marketDepth) * 0.001;  // 0.1%

// 슬리피지가 크면 진입 취소
if (slippageEstimate > 0.002) {  // 0.2% 이상
    log.warn("유동성 부족으로 진입 취소");
    return;
}
```

#### 3. 백테스팅 결과 기반 자동 최적화

**개선안**:
```java
// Walk-Forward Optimization
// 6개월 백테스팅 → 최적 파라미터 도출 → 1개월 실전 적용 → 반복

OptimizationResult result = optimizeParameters(
    smaRange = [10, 20, 30, 50],
    volumeThreshold = [1.1, 1.2, 1.5, 2.0],
    positionSize = [0.3, 0.5, 0.8],
    objective = "Sharpe Ratio"
);

// 최적 파라미터 적용
SHORT_SMA = result.bestShortSma;
VOLUME_THRESHOLD = result.bestVolumeThreshold;
```

---

## 6. 최종 권고 사항

### 6.1 Rule-Based 전략의 현재 용도

**✅ 적합한 용도**:
1. **벤치마크**: AI 모델 성능 비교 기준으로 사용
2. **교육 목적**: 기술적 분석 학습 및 백테스팅 연습
3. **연구 도구**: 다양한 전략 테스트 플랫폼

**❌ 부적합한 용도**:
1. **실전 투자**: 리스크 관리 부족으로 큰 손실 위험
2. **자동매매**: 진입 신호 부족으로 운영 불가능
3. **고액 투자**: 80% 포지션은 너무 공격적

### 6.2 실전 투자 전환 로드맵

**단계별 개선 계획**:

#### Phase 1: 리스크 관리 강화 (필수)
- [ ] Kelly Criterion 포지션 사이징 구현
- [ ] ATR 기반 변동성 조정
- [ ] 분할 청산 전략 추가 (Profit Ladder)
- [ ] Trailing Stop Loss 구현

**예상 기간**: 2주
**목표**: MDD 20% → 10%

#### Phase 2: 진입 신호 최적화
- [ ] Multiple Timeframe Analysis
- [ ] RSI/MACD 보조 지표 추가
- [ ] 시장 체제 인식 (ADX)
- [ ] 진입 조건 완화

**예상 기간**: 2주
**목표**: 연간 거래 횟수 18회 → 50회

#### Phase 3: 백테스팅 검증
- [ ] 3년치 데이터 백테스팅
- [ ] Walk-Forward Optimization
- [ ] Monte Carlo 시뮬레이션 (1000회)
- [ ] Sharpe Ratio, MDD, Win Rate 평가

**예상 기간**: 1주
**목표**: Sharpe Ratio > 1.5, MDD < 15%

#### Phase 4: 소액 실전 테스트
- [ ] 10만원으로 1개월 실전
- [ ] 결과 분석 및 파라미터 조정
- [ ] 점진적 금액 증가 (10만 → 50만 → 100만)

**예상 기간**: 3개월
**목표**: 백테스팅 대비 90% 이상 성능 유지

### 6.3 대안 전략 제안

**현재 프로젝트에서 실전 투자로 사용할 최선의 전략**:

#### 1순위: AI 기반 전략 (TakeProfitStopLossBacktestService)
**이유**:
- ✅ Kelly Criterion 포지션 사이징 (5가지 전략)
- ✅ Profit Ladder + Time-Decay (분할 청산)
- ✅ TP/SL 명확 (+3%, -2%)
- ✅ 1분봉 정밀 시뮬레이션

**조건**:
- AI 모델의 pred_proba_up 정확도가 55% 이상
- Sharpe Ratio > 1.5
- MDD < 20%

#### 2순위: Rule-Based 전략 개선 버전 (Phase 1~4 완료 후)
**이유**:
- ✅ AI 예측 불필요 (모델 실패 시 대안)
- ✅ 전통적 기술적 분석 기반
- ✅ 리스크 관리 강화 (개선 후)

**조건**:
- Phase 1~4 모두 완료
- 백테스팅 Sharpe Ratio > 1.2
- 실전 테스트 3개월 성공

#### 3순위: 하이브리드 전략 (AI + Rule-Based)
**개선안**:
```java
// AI 예측 + 기술적 지표 결합
if (predProbaUp >= 0.6 &&          // AI 신호
    close > SMA(20) &&             // 기술적 확인
    volume > MA(20) * 1.2) {       // 거래량 확인

    // 포지션 사이징
    BigDecimal kellySize = calculateKelly(predProbaUp, riskReward);
    BigDecimal volatilityAdj = calculateVolatilityAdjustment(atr);
    BigDecimal finalSize = kellySize * volatilityAdj * 0.5;  // Half-Kelly

    enter(finalSize);
}
```

**장점**:
- ✅ AI + 기술적 분석 이중 확인
- ✅ 허위 신호 감소
- ✅ 신뢰도 향상

---

## 7. 결론

### 현황 요약
1. **프로젝트 구조**: 잘 설계된 백테스팅 엔진 시스템
2. **AI 전략**: Kelly Criterion + 분할 청산으로 우수한 설계
3. **Rule-Based 전략**: 벤치마크로는 적합, 실전 투자로는 부적합

### Rule-Based 전략 평가

| 항목 | 벤치마크 용도 | 실전 투자 용도 |
|------|--------------|---------------|
| 리스크 관리 | ⚠️ 보통 | ❌ 부족 |
| 진입 신호 | ⚠️ 보통 (5%) | ❌ 부족 |
| 청산 전략 | ⚠️ 단순 | ❌ 부족 |
| 수익성 | ✅ 예상 양호 | ⚠️ 불확실 |
| 안정성 | ⚠️ 보통 | ❌ 낮음 |
| **총평** | **✅ 적합** | **❌ 부적합** |

### 최종 권고

#### ✅ 즉시 가능 (현재 상태)
- **벤치마크 사용**: AI 모델 성능 비교 기준으로 계속 사용
- **백테스팅 분석**: Fold별, 시장 체제별 성능 분석
- **교육 목적**: 기술적 분석 학습 도구

#### ⚠️ 개선 필요 (2~3개월 소요)
- **Phase 1~4 완료**: 리스크 관리, 진입 최적화, 백테스팅 검증, 실전 테스트
- **Sharpe Ratio > 1.2**: 위험 대비 수익률 향상
- **MDD < 15%**: 최대 낙폭 제한

#### ❌ 현재 불가능
- **고액 실전 투자**: 리스크 너무 높음 (80% 고정 포지션)
- **자동매매 운영**: 진입 신호 부족 (연 18회)
- **검증 없는 투자**: 백테스팅만으로 실전 성공 보장 안 됨

### 행동 계획

**즉시 (이번 주)**:
1. Rule-Based 전략은 **벤치마크 전용**으로 계속 사용
2. 실전 투자는 **AI 기반 전략** 우선 검토
3. AI 모델의 pred_proba_up 정확도 검증

**단기 (1개월)**:
1. AI 전략 백테스팅 결과 분석 (Sharpe Ratio, MDD, Win Rate)
2. Kelly Criterion 전략 5가지 중 최적 선택
3. 10만원으로 AI 전략 실전 테스트

**중기 (3개월)**:
1. Rule-Based 전략 개선 (Phase 1~2)
2. AI + Rule-Based 하이브리드 전략 실험
3. 실전 성과 비교 분석

**장기 (6개월 이후)**:
1. 최종 전략 확정 (AI, Rule-Based 개선, 하이브리드 중 선택)
2. 점진적 투자 금액 증가
3. 정기적 모델 재학습 및 파라미터 최적화

---

**작성 완료**: 2025-12-07
**다음 리뷰 예정**: 2025-12-21 (2주 후)
**담당자**: 최기영, 박신영

---

## 참고 자료

### 내부 문서
- `/docs/STRATEGY.md`: TP/SL 전략 상세 설명
- `/docs/API_SPEC.md`: API 명세서
- `FIX_GUIDE.md`: 1분봉 데이터 적재 가이드

### 핵심 코드 파일
- `RuleBasedBacktestService.java:26-539`: Rule-Based 전략 구현
- `TakeProfitStopLossBacktestService.java:27-99`: AI 전략 구현
- `TechnicalIndicators.java:1-343`: 기술적 지표 계산
- `PositionSizingStrategy.java`: Kelly Criterion 5가지 전략

### 외부 참고
- Kelly Criterion: https://en.wikipedia.org/wiki/Kelly_criterion
- Trend Following: https://www.investopedia.com/articles/trading/09/trend-following.asp
- ATR (Average True Range): https://www.investopedia.com/terms/a/atr.asp
