#!/usr/bin/env python3
"""
Kelly Criterion with Estimation Risk 구현 분석
- 현재 주어진 파라미터로 실제 적용 가능한지 검토
- σ_estimation² (추정 오차 분산) 계산 방법
"""

import math

def kelly_criterion(proba, R):
    """표준 Kelly Criterion"""
    if proba <= 0.5:
        return 0
    kelly = (R * proba - (1 - proba)) / R
    return max(0, min(1, kelly))

def estimation_risk_kelly_v1(pred_proba, confidence, R, risk_aversion=1.0):
    """
    방법 1: confidence를 estimation variance의 proxy로 사용

    핵심 아이디어:
    - confidence 높음 → 추정 오차 작음 → σ² 작음
    - confidence 낮음 → 추정 오차 큼 → σ² 큼

    σ²_estimation = k × (1 - confidence/max_confidence)²
    여기서 max_confidence = 0.5 (pred_proba가 0 또는 1일 때)

    Adjusted Kelly = Kelly × (1 - λ × σ²)
    """
    # confidence의 최대값은 0.5
    max_confidence = 0.5
    normalized_confidence = confidence / max_confidence  # 0~1 범위로 정규화

    # estimation variance: confidence 낮을수록 분산 높음
    # (1 - normalized_confidence)를 제곱하여 비선형 패널티
    sigma_squared = (1 - normalized_confidence) ** 2

    # Pure Kelly 계산
    pure_kelly = kelly_criterion(pred_proba, R)

    # Adjustment factor
    adjustment = 1 - risk_aversion * sigma_squared
    adjustment = max(0, min(1, adjustment))  # 0~1 범위로 제한

    # Adjusted Kelly
    adjusted_kelly = pure_kelly * adjustment

    return adjusted_kelly, sigma_squared, adjustment

def estimation_risk_kelly_v2(pred_proba, confidence, R, risk_aversion=2.0):
    """
    방법 2: Bayesian 분산 사용

    Beta Distribution의 분산:
    Var[p] = α × β / [(α + β)² × (α + β + 1)]

    여기서:
    - α, β는 Beta distribution의 파라미터
    - confidence가 높을수록 α + β가 커짐 (분산 작아짐)

    간소화:
    - pred_proba = α / (α + β)
    - confidence ∝ (α + β)  (총 관측 수)

    Var[p] ≈ p(1-p) / n
    여기서 n ∝ confidence
    """
    # confidence를 "effective sample size"로 해석
    # confidence가 0.5이면 충분한 샘플, 0에 가까우면 샘플 부족
    max_confidence = 0.5
    n_effective = 1 + confidence / max_confidence * 99  # 1~100 범위

    # Bayesian variance: p(1-p) / n
    sigma_squared = pred_proba * (1 - pred_proba) / n_effective

    # Pure Kelly
    pure_kelly = kelly_criterion(pred_proba, R)

    # Adjustment
    adjustment = 1 - risk_aversion * sigma_squared
    adjustment = max(0, min(1, adjustment))

    adjusted_kelly = pure_kelly * adjustment

    return adjusted_kelly, sigma_squared, adjustment

def estimation_risk_kelly_v3(pred_proba, confidence, R, risk_aversion=1.5):
    """
    방법 3: 하이브리드 - confidence와 확률 극단성 모두 고려

    핵심:
    1. confidence 낮음 → 추정 불확실
    2. pred_proba가 극단 (0 또는 1에 가까움) → 과대평가 위험

    σ² = (1 - normalized_conf) × extremeness_factor
    """
    max_confidence = 0.5
    normalized_confidence = confidence / max_confidence

    # 극단성 측정: 0.5에서 멀수록 극단적
    extremeness = abs(pred_proba - 0.5) * 2  # 0~1 범위

    # 복합 리스크: 확신 낮고 + 극단 예측 → 높은 리스크
    sigma_squared = (1 - normalized_confidence) * (0.5 + 0.5 * extremeness)

    pure_kelly = kelly_criterion(pred_proba, R)

    adjustment = 1 - risk_aversion * sigma_squared
    adjustment = max(0, min(1, adjustment))

    adjusted_kelly = pure_kelly * adjustment

    return adjusted_kelly, sigma_squared, adjustment

print("=" * 100)
print("Kelly Criterion with Estimation Risk: 실제 구현 가능성 분석")
print("=" * 100)

print("\n📐 이론적 배경")
print("-" * 100)
print("""
원래 공식:
    Adjusted Kelly = Kelly(p̂) × (1 - λ × σ²_estimation)

여기서:
- p̂: AI 예측 확률 (pred_proba_up)
- σ²_estimation: 추정 오차의 분산
- λ: 리스크 회피 계수 (risk aversion)

핵심 질문: σ²_estimation을 어떻게 구하나?
→ 우리가 갖고 있는 파라미터: pred_proba_up, confidence

3가지 구현 방법 제시:
1. confidence를 variance proxy로 직접 사용
2. Bayesian variance (Beta distribution)
3. 하이브리드 (confidence + 극단성)
""")

print("\n🔍 구체적 예시 비교")
print("-" * 100)

R = 2.0
test_cases = [
    {"pred": 0.9, "conf": 0.4, "name": "높은 확률, 중간 확신"},
    {"pred": 0.8, "conf": 0.3, "name": "높은 확률, 낮은 확신"},
    {"pred": 0.7, "conf": 0.2, "name": "중간 확률, 낮은 확신"},
    {"pred": 0.6, "conf": 0.1, "name": "임계값, 매우 낮은 확신"},
]

for tc in test_cases:
    pred = tc["pred"]
    conf = tc["conf"]

    pure_kelly = kelly_criterion(pred, R)
    current = pure_kelly * conf  # 현재 방식

    v1_kelly, v1_sigma, v1_adj = estimation_risk_kelly_v1(pred, conf, R)
    v2_kelly, v2_sigma, v2_adj = estimation_risk_kelly_v2(pred, conf, R)
    v3_kelly, v3_sigma, v3_adj = estimation_risk_kelly_v3(pred, conf, R)

    print(f"\n{tc['name']}")
    print(f"  pred_proba={pred:.2f}, confidence={conf:.2f}, R={R:.1f}")
    print(f"  ───────────────────────────────────────────────────────────────")
    print(f"  Pure Kelly:              {pure_kelly:6.2%}")
    print(f"  현재 (Kelly×conf):       {current:6.2%}")
    print(f"  ")
    print(f"  방법 1 (σ²={v1_sigma:.4f}): {v1_kelly:6.2%}  (adjustment={v1_adj:.3f})")
    print(f"  방법 2 (σ²={v2_sigma:.4f}): {v2_kelly:6.2%}  (adjustment={v2_adj:.3f})")
    print(f"  방법 3 (σ²={v3_sigma:.4f}): {v3_kelly:6.2%}  (adjustment={v3_adj:.3f})")

print("\n" + "=" * 100)
print("각 방법의 특징")
print("=" * 100)

print("""
방법 1: confidence를 variance proxy로
────────────────────────────────────
σ² = (1 - confidence/0.5)²

✅ 장점:
   - 가장 단순함
   - confidence가 낮으면 직접적으로 패널티
   - 구현 쉬움

❌ 단점:
   - pred_proba의 극단성을 고려하지 않음
   - confidence = 0일 때 σ² = 1 (너무 극단적)

방법 2: Bayesian variance (Beta distribution)
────────────────────────────────────────────
σ² = pred_proba × (1-pred_proba) / n_effective
여기서 n_effective ∝ confidence

✅ 장점:
   - 이론적으로 가장 타당 (Bayesian)
   - pred_proba 자체의 불확실성도 반영
   - confidence를 "샘플 크기"로 해석

❌ 단점:
   - σ²가 작아서 adjustment가 약함
   - risk_aversion 파라미터 튜닝 필요

방법 3: 하이브리드
──────────────────
σ² = (1 - normalized_conf) × (0.5 + 0.5 × extremeness)

✅ 장점:
   - confidence와 극단성 모두 고려
   - 균형잡힌 조정
   - 직관적

❌ 단점:
   - 파라미터가 임의적
   - 수학적 근거가 방법 2보다 약함
""")

print("\n" + "=" * 100)
print("전체 비교: 모든 방법 vs 현재 방식")
print("=" * 100)

print(f"\n{'pred':<6} {'conf':<6} {'Pure':<8} {'현재':<8} {'방법1':<8} {'방법2':<8} {'방법3':<8}")
print("-" * 60)

for pred in [0.9, 0.8, 0.7, 0.6]:
    for conf in [0.4, 0.3, 0.2, 0.1]:
        pure = kelly_criterion(pred, R)
        curr = pure * conf
        v1, _, _ = estimation_risk_kelly_v1(pred, conf, R)
        v2, _, _ = estimation_risk_kelly_v2(pred, conf, R)
        v3, _, _ = estimation_risk_kelly_v3(pred, conf, R)

        print(f"{pred:<6.2f} {conf:<6.2f} {pure:<8.2%} {curr:<8.2%} {v1:<8.2%} {v2:<8.2%} {v3:<8.2%}")

print("\n" + "=" * 100)
print("실전 적용 가능성 평가")
print("=" * 100)

print("""
질문: "현재 주어진 파라미터로 구현 가능한가?"

답: ✅ 가능하다!

필요한 것:
1. pred_proba_up ✅ (있음)
2. confidence ✅ (있음)
3. risk_aversion 파라미터 (설정 가능, 예: 1.0~2.0)

추천 방법:
───────────

🥇 **방법 2 (Bayesian variance)** - 가장 이론적으로 타당
   - σ² = p(1-p) / n_effective
   - n_effective = f(confidence)
   - risk_aversion = 2.0~3.0

🥈 **방법 3 (하이브리드)** - 균형잡힌 실무적 선택
   - confidence와 극단성 모두 고려
   - risk_aversion = 1.5

🥉 **방법 1 (단순)** - 가장 구현 쉬움
   - 하지만 너무 극단적일 수 있음
   - risk_aversion = 1.0

구현 예시 (Java):
─────────────────

// 방법 2: Bayesian variance
private BigDecimal estimationRiskKelly(
    BigDecimal predProbaUp,
    BigDecimal confidence,
    BigDecimal R
) {
    BigDecimal MAX_CONF = new BigDecimal("0.5");
    BigDecimal RISK_AVERSION = new BigDecimal("2.0");

    // n_effective = 1 + (confidence/0.5) × 99
    BigDecimal nEffective = BigDecimal.ONE.add(
        confidence.divide(MAX_CONF, 8, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("99"))
    );

    // σ² = p(1-p) / n
    BigDecimal variance = predProbaUp
        .multiply(BigDecimal.ONE.subtract(predProbaUp))
        .divide(nEffective, 8, RoundingMode.HALF_UP);

    // Pure Kelly
    BigDecimal kelly = calculateKellyPosition(predProbaUp, R);

    // Adjustment = 1 - λ × σ²
    BigDecimal adjustment = BigDecimal.ONE.subtract(
        RISK_AVERSION.multiply(variance)
    );
    adjustment = adjustment.max(BigDecimal.ZERO).min(BigDecimal.ONE);

    // Adjusted Kelly
    return kelly.multiply(adjustment);
}

장점:
─────
✅ 현재 파라미터만으로 구현 가능
✅ 수학적으로 타당함
✅ risk_aversion으로 조절 가능
✅ 현재 방식보다 덜 보수적
✅ Conservative Kelly보다 이론적 근거 강함

단점:
─────
⚠️ risk_aversion 파라미터 튜닝 필요
⚠️ 백테스팅으로 최적값 찾아야 함
⚠️ Conservative Kelly보다 복잡함
""")

print("\n" + "=" * 100)
print("최종 추천")
print("=" * 100)

print("""
3가지 옵션:

옵션 A: Conservative Kelly (확률 조정)
  adjusted_proba = pred × conf + 0.5 × (1-conf)
  position = Kelly(adjusted_proba)

  ✅ 가장 단순
  ✅ 이론적 근거 강함 (Bayesian Shrinkage)
  ✅ 파라미터 튜닝 불필요

옵션 B: Estimation Risk Kelly (방법 2)
  σ² = p(1-p) / n_effective(confidence)
  position = Kelly(p) × (1 - λ × σ²)

  ✅ 가장 이론적 (Parameter Uncertainty)
  ✅ 금융 수학에서 검증됨
  ⚠️ risk_aversion 튜닝 필요

옵션 C: Half Kelly (가장 단순)
  position = Kelly(p) × 0.5

  ✅ 업계 표준
  ✅ confidence 무시
  ✅ 가장 안정적

💡 실전 추천 순서:
1. 먼저 Conservative Kelly로 백테스팅
2. Estimation Risk Kelly (방법 2)로 비교
3. 성능 좋은 쪽 선택
4. risk_aversion 파라미터 최적화
""")
