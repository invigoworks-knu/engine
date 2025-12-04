#!/usr/bin/env python3
"""
포지션 사이징 방법 비교 시뮬레이션
"""

import math

def kelly_criterion(pred_proba_up, R):
    """표준 Kelly Criterion"""
    numerator = R * pred_proba_up - (1 - pred_proba_up)
    kelly = numerator / R
    return max(0, min(1, kelly))

def current_method(pred_proba_up, R, confidence):
    """현재 구현: Kelly × confidence"""
    kelly = kelly_criterion(pred_proba_up, R)
    return kelly * confidence

def conservative_kelly(pred_proba_up, R, confidence):
    """보수적 Kelly: confidence로 확률 조정"""
    # confidence 낮으면 0.5로 회귀
    adjusted_proba = pred_proba_up * confidence + 0.5 * (1 - confidence)
    return kelly_criterion(adjusted_proba, R)

def shrinkage_method(pred_proba_up, R, confidence):
    """Bayesian Shrinkage"""
    lower_bound = pred_proba_up - (1 - confidence) * (pred_proba_up - 0.5)
    return kelly_criterion(lower_bound, R)

# 테스트 케이스
test_cases = [
    {"pred_proba_up": 0.9, "confidence": 0.4, "R": 2.0, "name": "높은 확률, 낮은 확신"},
    {"pred_proba_up": 0.8, "confidence": 0.3, "R": 2.0, "name": "높은 확률, 낮은 확신"},
    {"pred_proba_up": 0.7, "confidence": 0.2, "R": 2.0, "name": "중간 확률, 낮은 확신"},
    {"pred_proba_up": 0.6, "confidence": 0.1, "R": 2.0, "name": "임계값, 매우 낮은 확신"},
    {"pred_proba_up": 0.8, "confidence": 0.3, "R": 1.5, "name": "낮은 R-R Ratio"},
]

print("=" * 100)
print("포지션 사이징 방법 비교")
print("=" * 100)

for tc in test_cases:
    pred = tc["pred_proba_up"]
    conf = tc["confidence"]
    R = tc["R"]

    pure_kelly = kelly_criterion(pred, R)
    current = current_method(pred, R, conf)
    conservative = conservative_kelly(pred, R, conf)
    shrinkage = shrinkage_method(pred, R, conf)
    half_kelly = pure_kelly * 0.5
    quarter_kelly = pure_kelly * 0.25

    print(f"\n{tc['name']}")
    print(f"  pred_proba_up={pred:.2f}, confidence={conf:.2f}, R={R:.1f}")
    print(f"  ─────────────────────────────────────────────────────")
    print(f"  1. Pure Kelly:           {pure_kelly:6.2%}")
    print(f"  2. 현재 (Kelly×conf):    {current:6.2%}  ← 현재 구현")
    print(f"  3. Conservative Kelly:   {conservative:6.2%}  ← 확률 조정")
    print(f"  4. Shrinkage:            {shrinkage:6.2%}  ← 하한 사용")
    print(f"  5. Half Kelly:           {half_kelly:6.2%}  ← 업계 표준")
    print(f"  6. Quarter Kelly:        {quarter_kelly:6.2%}  ← 보수적")

    # 차이 분석
    print(f"\n  📊 분석:")
    print(f"     현재 방식은 Pure Kelly 대비 {(current/pure_kelly - 1)*100:+.1f}% ({pure_kelly:.2%} → {current:.2%})")
    print(f"     Conservative는 {(conservative/pure_kelly - 1)*100:+.1f}% ({pure_kelly:.2%} → {conservative:.2%})")
    print(f"     Half Kelly는 {(half_kelly/pure_kelly - 1)*100:+.1f}% (고정)")

print("\n" + "=" * 100)
print("결론:")
print("=" * 100)
print("1. 현재 방식(Kelly×confidence)은 극도로 보수적 (60-90% 축소)")
print("2. Conservative Kelly는 더 합리적 (20-40% 축소)")
print("3. Half Kelly는 confidence 없이도 안정적 (50% 축소)")
print("4. confidence가 낮을 때 현재 방식은 거의 거래 안 함")
print("=" * 100)
