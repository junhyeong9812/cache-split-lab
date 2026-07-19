# OVERVIEW: arm D — 레디스 외부화 구현 + 실측

## 주요 포인트 (5)

- 캐시 계층을 인터페이스(KeyCache)로 올리고 구현 선택을 설정(CACHE_MODE)으로 내렸다 —
  기존 arm A/B/C 는 diff 영향 0. → 선택 `changelog J-1·J-3`
- 공유 Redis 위에 **엔트리 수 정확 LRU** 를 Lua 로 직접 구현했다 — Redis 기본 축출은
  근사라 금지. 핵심은 단조 카운터와 스크립트 원자성. → 메커니즘 `TECHNICAL §개념 1~3`
- 측정 전 게이트로 용량 3 축출 시나리오를 통과시킨 뒤에만 부하를 걸었다 — "조용히
  틀린 LRU" 차단. → task.md §3
- 선행 페이즈의 절차 교훈 3건(계단 30s 명시·복합 판정 규칙·측정 시드 워밍업)을 spec 에
  사전 등록하고 그대로 집행했다. → `phase-d-redis/spec.md §2`
- 결론: **외부화의 대가 = 지연(+0.08ms)이 아니라 용량(무릎 −40%) + 과부하 험한 붕괴.**
  4 arm 지도 완성. → `phase-d-redis/gate.md 종합`

## 워크플로우 (절차 + 분기)

```
(TODO 3번 · 사용자 결정: CPU 총량 고정에 redis 포함)
  │
  ▼
[정의 게이트: task.md 6칸+트리아지] ──▶ [구현: KeyCache/RedisLruCache/compose/runner]
  ▼
[빌드+기존 테스트] ── 실패? ──▶ (수정 — 미발생)
  ▼
[배포: deploy.sh → BPC·APC] ──▶ [arm D 기동] ──▶ [스모크: 노드1 미스→노드2 히트?]
  ▼                                                   └─ 아니오 ─▶ (공유 배선 결함 수색 — 미발생)
[Lua LRU 게이트: 용량3 시나리오] ── 축출 순서 틀림? ──▶ (측정 진입 금지 — 미발생)
  ▼
[phase-d spec 사전 등록 (측정 전)] ──▶ [계단 2000~6000×30s + 60s 지점]
  ▼
[첫 계단 과도 상태 발견] ──▶ [저부하 4계단 정상상태 재계단 (기준 불변·병기)]
  ▼
[P1~P4 판정 → result·gate] ──▶ (기록: README·TODO·TIMELINE·4종 문서·리뷰·측정로그)
```

## 딥다이브 인덱스

| 알고 싶은 것 | 문서·절 |
|---|---|
| 왜 그렇게 동작하나 (근사 LRU 금지·Lua 원자성·비용 구조) | `TECHNICAL.md` |
| 이번 diff 의 선택·대안 (인터페이스·Lua 설계·compose·러너) | `changelog.md` J-1~J-5 |
| 무슨 요소를 어떻게 썼나 (Lettuce·Lua·redis 설정) | `learned.md` |
| 실측 수치·판정 | `docs/phases/phase-d-redis/` spec→result→gate |
| 리뷰 finding 과 처리 | `review-log.md` |
