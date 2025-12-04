#!/usr/bin/env python3
"""
Conservative Kelly 상세 분석
- Bayesian Shrinkage 원리
- 확률 조정의 수학적 근거
"""

def conservative_kelly_adjustment(pred_proba, confidence):
    """
    Conservative Kelly: 확률 조정

    adjusted_proba = pred_proba × confidence + 0.5 × (1 - confidence)

    이것은 가중평균(weighted average):
    - confidence만큼: AI 예측 신뢰
    - (1-confidence)만큼: 중립(0.5)으로 회귀
    """
    return pred_proba * confidence + 0.5 * (1 - confidence)

def kelly_criterion(proba, R):
    """Kelly Criterion 계산"""
    if proba <= 0.5:
        return 0
    kelly = (R * proba - (1 - proba)) / R
    return max(0, min(1, kelly))

print("=" * 100)
print("Conservative Kelly: 확률 조정 방식 상세 분석")
print("=" * 100)

print("\n📐 수학적 근거: Bayesian Shrinkage")
print("-" * 100)
print("""
핵심 아이디어:
1. AI의 예측 확률(pred_proba_up)은 "추정치"이지 "참값"이 아님
2. confidence가 낮으면 = 이 추정치를 믿기 어려움
3. 믿기 어려운 예측은 "중립값(0.5)"에 가까워져야 함
4. confidence가 높으면 = 예측을 신뢰 = 그대로 사용

수식:
adjusted_proba = pred_proba × confidence + 0.5 × (1 - confidence)

이것은 "가중평균 (Weighted Average)":
- AI 예측에 confidence만큼 가중치
- 중립값(0.5)에 (1-confidence)만큼 가중치
""")

print("\n🔍 구체적 예시:")
print("-" * 100)

test_cases = [
    {"pred": 0.9, "conf": 0.0, "name": "예측 90%, 하지만 확신 0%"},
    {"pred": 0.9, "conf": 0.2, "name": "예측 90%, 확신 20%"},
    {"pred": 0.9, "conf": 0.4, "name": "예측 90%, 확신 40%"},
    {"pred": 0.9, "conf": 0.8, "name": "예측 90%, 확신 80%"},
    {"pred": 0.9, "conf": 1.0, "name": "예측 90%, 확신 100%"},
]

for tc in test_cases:
    pred = tc["pred"]
    conf = tc["conf"]
    adjusted = conservative_kelly_adjustment(pred, conf)

    print(f"\n{tc['name']}")
    print(f"  pred_proba_up = {pred:.2f}")
    print(f"  confidence    = {conf:.2f}")
    print(f"  ───────────────────────────────")
    print(f"  계산: {pred:.2f} × {conf:.2f} + 0.5 × {1-conf:.2f}")
    print(f"      = {pred*conf:.3f} + {0.5*(1-conf):.3f}")
    print(f"      = {adjusted:.3f}")
    print(f"  ───────────────────────────────")
    print(f"  해석: AI가 {pred:.0%}라고 했지만,")
    print(f"        확신이 {conf:.0%}밖에 안 되니까")
    print(f"        실제로는 {adjusted:.1%}로 조정")

print("\n" + "=" * 100)
print("왜 이게 '이론적으로 타당'한가?")
print("=" * 100)

print("""
1. Bayesian 관점: Prior와 Likelihood의 조합
   ─────────────────────────────────────
   - Prior (사전 확률): 0.5 (중립, 아무 정보 없을 때)
   - Likelihood (관측): AI 예측 (pred_proba_up)
   - Confidence: Likelihood의 신뢰도

   confidence 높음 → Likelihood를 많이 믿음 → Prior에서 멀어짐
   confidence 낮음 → Likelihood를 안 믿음 → Prior로 회귀

   이것은 Bayesian Update의 간소화 버전

2. Credibility Theory (신뢰도 이론)
   ─────────────────────────────────────
   보험/금융 수학에서 사용하는 방법:

   최종 추정치 = Z × 관측값 + (1-Z) × 사전 추정치

   여기서 Z = credibility factor (신뢰도)
   → 우리의 confidence와 정확히 같은 역할!

3. Shrinkage Estimator (축소 추정량)
   ─────────────────────────────────────
   통계학에서 검증된 방법:

   극단적인 추정치는 중심으로 "shrink"시키면 더 정확함
   (James-Stein Estimator의 원리)

   pred_proba = 0.95 (극단)
   confidence = 0.3 (낮음)
   → 0.5로 shrink → 0.635

4. Kelly Criterion with Parameter Uncertainty
   ─────────────────────────────────────────────
   금융 이론:

   확률 p를 정확히 모를 때,
   - 최선의 추정치: p̂
   - 불확실성: σ²

   → Conservative 접근: p̂를 중립값으로 조정
   → 우리의 방법과 정확히 일치
""")

print("\n" + "=" * 100)
print("현재 방식 vs Conservative Kelly 비교")
print("=" * 100)

R = 2.0
comparison_cases = [
    {"pred": 0.9, "conf": 0.4},
    {"pred": 0.8, "conf": 0.3},
    {"pred": 0.7, "conf": 0.2},
    {"pred": 0.6, "conf": 0.1},
]

print(f"\n(Risk-Reward Ratio R = {R})\n")
print(f"{'pred_proba':<12} {'confidence':<12} {'현재 방식':<15} {'Conservative':<15} {'차이':<10}")
print("-" * 70)

for tc in comparison_cases:
    pred = tc["pred"]
    conf = tc["conf"]

    # 현재 방식
    pure_kelly = kelly_criterion(pred, R)
    current = pure_kelly * conf

    # Conservative Kelly
    adjusted_proba = conservative_kelly_adjustment(pred, conf)
    conservative = kelly_criterion(adjusted_proba, R)

    diff = conservative - current

    print(f"{pred:<12.2f} {conf:<12.2f} {current:<15.2%} {conservative:<15.2%} {diff:>+9.2%}")

print("\n💡 해석:")
print("   Conservative Kelly가 훨씬 덜 극단적")
print("   → 기회를 놓치지 않으면서도 리스크 관리")


print("\n" + "=" * 100)
print("결론")
print("=" * 100)
print("""
Conservative Kelly (확률 조정 방식):

✅ 수학적 근거:
   - Bayesian Shrinkage
   - Credibility Theory
   - Parameter Uncertainty

✅ 실무적 효과:
   - 과도한 포지션 축소 방지
   - AI 과신 문제 해결
   - 기회와 리스크 균형

✅ 구현 간단:
   adjusted_proba = pred × conf + 0.5 × (1-conf)
   position = Kelly(adjusted_proba)

❌ 현재 방식 (Kelly × confidence):
   - 이론적 근거 약함
   - 포지션을 직접 곱하는 건 임의적
   - 결과가 너무 보수적

💡 추천: Conservative Kelly 방식으로 변경
""")
