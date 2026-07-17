#!/usr/bin/env python3
"""LRU 히트율 이론 예측 — 이 프로젝트의 **기준소스**.

Che's approximation (Che·Tung·Wang 2002 / Fagin 1977 계열).
근거·출처·검증은 docs/theory/lru-zipf.md.

실측이 이 값과 어긋나면 셋 중 하나다:
  ① 리그가 고장났다  ② 근사의 가정(IRM)이 깨졌다  ③ 이론이 부족하다
어느 쪽인지는 gate.md 에서 가린다. 무엇이든 **모르고 넘어가지는 않는다.**
"""
import numpy as np


def zipf_popularities(n: int, s: float) -> np.ndarray:
    """Zipf(s) 정규화 인기도: q_i ∝ 1/i^s (i=1..n), 합 = 1. s=0 이면 균등."""
    ranks = np.arange(1, n + 1, dtype=float)
    weights = np.ones(n) if s == 0 else ranks ** (-s)
    return weights / weights.sum()


def che_hit_rate(n: int, s: float, c: float) -> float:
    """Che's approximation — IRM + LRU 하 예상 히트율.

    characteristic time t_C 는 다음의 유일한 해:
        Σ_i (1 - e^{-λ_i·t}) = C
    좌변이 t 에 대해 단조증가(0 → n)하므로 이분법으로 안정적으로 풀린다.

    그 다음:
        h_i = 1 - e^{-λ_i·t_C}          (아이템별 히트 확률)
        h   = Σ_i λ_i·h_i               (전체 히트율)

    가정: IRM(Independent Reference Model) — 매 요청이 독립적으로 인기도 분포에서 뽑힘.
          우리 k6 부하가 정확히 이 모델이다(매 요청 독립 Zipf 추출).
    """
    if c >= n:
        return 1.0                      # 캐시가 키 공간 이상 → 축출 없음 → 전부 히트(정상상태)
    lam = zipf_popularities(n, s)

    def g(t: float) -> float:
        return float(np.sum(1.0 - np.exp(-lam * t)) - c)

    lo, hi = 0.0, 1.0
    while g(hi) < 0:
        hi *= 2.0
    for _ in range(200):                # 이분법 — 단조성이 유일해를 보장
        mid = (lo + hi) / 2
        if g(mid) < 0:
            lo = mid
        else:
            hi = mid
    t_c = (lo + hi) / 2
    return float(np.sum(lam * (1.0 - np.exp(-lam * t_c))))


def static_top_c(n: int, s: float, c: int) -> float:
    """상위 C 개를 **항상** 담고 있는 oracle 캐시의 히트율.

    ⚠️ **이건 LRU 가 아니다.** s=1 에서 ≈ ln(C)/ln(N) 으로 알려진 그 값이고,
    실제 LRU 는 이보다 **나쁘다**(Berthet 2017: α=1 에서 LRU miss 가 static 의 최대 1.43배).
    비교용으로만 남긴다 — 예측에 쓰면 낙관 편향이 된다.
    """
    p = zipf_popularities(n, s)
    return float(np.sort(p)[::-1][:c].sum())


# ── arm 별 예측 ──────────────────────────────────────────────────────────
#
# 핵심 통찰: **arm B 는 "캐시 C/2 짜리 단일 노드"와 통계적으로 같다.**
#   라운드로빈은 분포를 보존하므로 각 노드가 보는 스트림이 전체 스트림과 동일하다.
#   → 요청 입장에서: 캐시 C/2 인 노드에 떨어지고, 그 노드의 캐시는 전체와 같은 분포로 채워짐.
#
# **arm C 는 "캐시 C/2, 키 공간 N/2"** 다. 둘 다 절반이라 비율이 보존된다.
#   → A 와 (거의) 같은 히트율. 대가는 히트율이 아니라 **부하 쏠림**으로 나온다.

def predict(n: int, s: float, cache_total: int) -> dict:
    a = che_hit_rate(n, s, cache_total)
    b = che_hit_rate(n, s, cache_total // 2)              # 분포 보존 → C/2 단일 캐시와 동등
    c = che_hit_rate(n // 2, s, cache_total // 2)         # 키 공간도 절반
    return {
        "keyspace": n, "skew": s, "cache_total": cache_total,
        "ratio": round(n / cache_total, 4),
        "A": round(a, 4), "B": round(b, 4), "C": round(c, 4),
        "loss_A_to_B": round(a - b, 4),
        "loss_A_to_C": round(a - c, 4),
        "static_envelope_B": round(static_top_c(n, s, cache_total // 2), 4),
    }


if __name__ == "__main__":
    import json, sys
    CACHE_TOTAL = 10_000
    if len(sys.argv) > 1 and sys.argv[1] == "--json":
        out = [predict(n, s, CACHE_TOTAL)
               for s in (0.0, 1.0, 2.0) for n in (5_000, 10_000, 20_000, 40_000, 80_000)]
        print(json.dumps(out, indent=2))
    else:
        for s in (0.0, 1.0, 2.0):
            print(f"\n═══ s = {s}  (캐시 총량 {CACHE_TOTAL:,} 고정) ═══")
            print(f"{'비율':>5} {'키공간':>8} | {'A':>7} {'B':>7} {'C':>7} | "
                  f"{'손실 A→B':>9} {'손실 A→C':>9} | {'static(낙관)':>12}")
            print("-" * 82)
            for n in (5_000, 10_000, 20_000, 40_000, 80_000):
                p = predict(n, s, CACHE_TOTAL)
                print(f"{p['ratio']:>5} {n:>8,} | {p['A']:>7.4f} {p['B']:>7.4f} {p['C']:>7.4f} | "
                      f"{p['loss_A_to_B']*100:>8.2f}%p {p['loss_A_to_C']*100:>8.2f}%p | "
                      f"{p['static_envelope_B']:>12.4f}")
