# 학습 기록 (Learned)

> 작성일: 2026-07-19
> 관련 산출물: `docs/plans/2026-07-18/phase4-saturation-crosspoint/task.md`
> 작업 요약: SharedArray 2차 결함 수정 + 세 arm 포화점(Phase 0)·부하 구간 지도(Phase 4) 실측

---

## 1. 사용된 라이브러리

| 라이브러리 | 버전 | 용도 | 왜 선택했는가 |
|-----------|------|------|-------------|
| k6 (`grafana/k6:latest` docker) | v1.x (2026-07 시점 latest) | 부하 생성·지연/드롭 집계 | 기존 채택(open-model 실행기 — DECISIONS) |
| k6 `k6/data` SharedArray | k6 내장 | CDF 를 VU 간 공유 | VU 마다 CDF 사본이 생기면 생성기가 죽는다(두 번 실증) |
| docker `-m/--memory-swap` | 29.x | 생성기 컨테이너 메모리 캡 | 최악 실패를 "호스트 동결"에서 "컨테이너 사망"으로 격하 |
| python3 (표준 json/re) | 3.12 | k6 summary 파싱·계단 분석 | 추가 의존성 불필요 |

---

## 2. 핵심 함수 / 메서드

### k6 — k6/data · scenarios

| 함수/메서드 | 시그니처 | 역할 | 사용 위치 |
|------------|---------|------|----------|
| `new SharedArray(name, fn)` | `(string, () => any[])` | init 1회 구축, 전 VU 공유(접근당 원소 복사) | `k6/load.js:50`, `k6/probe.js:34`, `k6/verify-zipf.js:14` |
| `constant-arrival-rate` | scenario executor | 도착률 고정(open model) | `k6/load.js:31`, `k6/probe.js:23` |
| `samplerFromCdf(cdf, n, seed)` | `(indexable\|null, int, int) => () => int` | CDF 이분탐색 샘플러 (null=균등) | `k6/zipf.js:72` |

**사용 예시:**
```js
// ★ CDF 는 원소 40k 개짜리 SharedArray — 원소 1개로 감싸면 VU 마다 전체가
//   복사돼 부하 생성기가 죽는다 (zipf.js 경고·TIMELINE.md 문제 ⑥).
const CDF = new SharedArray('cdf', () => buildCdf(N, SKEW) || []);
// VU 마다 자기 시드 → 재현 가능. 전체 합은 Zipf.
const sampler = samplerFromCdf(SKEW === 0 ? null : CDF, N, SEED + __VU);
```
- 출처: `k6/load.js:48-52`

**코드 설명:**
> `SharedArray('cdf', fn)` — fn 을 첫 VU 초기화 때 1회 실행해 결과 배열을 공유 저장.
> 반환은 프록시: 인덱스 접근마다 **그 원소만** 역직렬화 사본을 준다. 콜백은 배열 필수 → s=0(null)은 `|| []`.
> `samplerFromCdf(cdf, …)` — cdf 가 null 이면 균등 경로, 아니면 이분탐색(~17회 원소 접근/샘플).
> `SEED + __VU` — VU 별 독립 시드로 재현 가능한 스트림(arm 간 동일 부하의 전제).

---

## 3. 어노테이션 / 데코레이터

| 어노테이션/데코레이터 | 소속 | 역할 | 적용 대상 |
|--------------------|------|------|----------|
| 해당 없음 | — | JS 스크립트·셸만 수정 | — |

**동작 원리:** 해당 없음.

---

## 4. 수정 전/후 코드 비교

### 파일명: `k6/load.js` (probe.js 동일 패턴)

**수정 전:**
```js
// ★ CDF 를 SharedArray 로 VU 간 1회만 구축한다. VU 마다 만들면 부하 생성기가 죽는다.
const CDF = new SharedArray('cdf', () => [buildCdf(N, SKEW)]);
// VU 마다 자기 시드 → 재현 가능. 전체 합은 Zipf.
const sampler = samplerFromCdf(CDF[0], N, SEED + __VU);
```

**수정 후:**
```js
// ★ CDF 는 원소 40k 개짜리 SharedArray — 원소 1개로 감싸면 VU 마다 전체가
//   복사돼 부하 생성기가 죽는다 (zipf.js 경고·TIMELINE.md 문제 ⑥).
const CDF = new SharedArray('cdf', () => buildCdf(N, SKEW) || []);
// VU 마다 자기 시드 → 재현 가능. 전체 합은 Zipf.
const sampler = samplerFromCdf(SKEW === 0 ? null : CDF, N, SEED + __VU);
```

**변경 이유:** `[buildCdf(...)]` 는 40k 배열 전체가 원소 1개 — `CDF[0]` 접근 순간 VU 마다
전체 사본(~5MB)이 생겨 "공유"가 무효(1,250 VU × 5MB ≈ 6GB OOM). 원소를 스칼라 40k 개로
풀면 접근당 숫자 하나만 복사(실측 464MB).

**변경된 함수/메서드 설명:**
| 함수/메서드 | 변경 내용 | 이유 |
|------------|----------|------|
| CDF 구축 | 원소 1개 래핑 → 원소 40k 개 + `\|\| []` | 접근 단위 = 복사 단위 |
| sampler 인자 | `CDF[0]` → `SKEW === 0 ? null : CDF` | s=0 균등 경로 보존(빈 SharedArray 를 CDF 로 오인 금지) |

### 파일명: `k6/probe.js` (VU 과할당)

**수정 전:**
```js
    preAllocatedVUs: Math.min(3000, Math.max(100, rps)),
    maxVUs: 6000,
```

**수정 후:**
```js
    preAllocatedVUs: Math.min(500, Math.max(50, Math.ceil(rps / 50))),
    maxVUs: 1500,
```

**변경 이유:** preAllocatedVUs 는 시나리오 수만큼 곱해져 초기화에 전부 생성(5계단 × 3,000
= 15,000 VU → 호스트 동결). VU 수요 = rate × 지연(Little's law)이라 rps/50(지연 20ms
가정)이면 충분 — 부족하면 maxVUs 까지 늘다 드롭으로 드러나고, 그 드롭이 곧 포화 신호다.

### 파일명: `k6/verify-zipf.js` (검정 추가 — 신규 항목)

**수정 후(추가분):**
```js
  // SharedArray 경로 ≡ 일반 배열 경로 (같은 시드 → 같은 시퀀스)
  const plain  = samplerFromCdf(buildCdf(SHARED_N, SHARED_S), SHARED_N, 11);
  const shared = samplerFromCdf(SHARED_CDF, SHARED_N, 11);
  let sharedSame = true;
  for (let i = 0; i < 100000; i++) if (plain() !== shared()) { sharedSame = false; break; }
```
**변경 이유:** 바뀐 표면(SharedArray 경유 이분탐색)을 기존 검정이 안 봄 — JSON float
왕복이 흔들리면 조용히 다른 분포가 된다. 실행 결과 PASS.

---

## 5. 동작 구조

### 실행 흐름

```
조종 노트북 (run 스크립트)
  → ssh APC: docker run k6 (-m 6g 캡, 계단 = 런 분리)
    → nginx (BPC:80, upstream = arm 구성)
      → app 노드(들) (CPU 쿼터 0.5 또는 0.25×2, LRU 캐시)
        → (miss 시) origin (5ms 지연, 4CPU)
  ← k6 summary(JSON) → 노트북 파서(p0_analyze) → 무릎 판정·노드 부하 몫
  (계단 사이) 노트북 → BPC /admin/stats 스냅샷(.pre/.post) — 노드별 hits/misses 델타
```

### 컴포넌트별 역할

| 컴포넌트 | 파일 | 역할 | 호출하는 메서드 |
|----------|------|------|---------------|
| 부하 스크립트 | `k6/load.js` | RPS 고정 1계단 | `http.get`, sampler |
| 계단 러너 | scratchpad `p0-arm.sh` 계열 | arm 기동→프리웜→워밍업→계단→스냅샷 | ssh·docker compose·curl |
| 분석기 | scratchpad `p0_analyze.py` | summary 파싱·무릎 판정·부하 몫 | `raw_decode` |
| 검정 | `k6/verify-zipf.js` | 분포 정확도·경로 동등성 | `zipfSampler`, `samplerFromCdf` |

### 데이터 흐름

```
STEPS(RPS 목표)
  → k6 summary-export JSON (http_req_duration p50/p95 · dropped · failed · cache_hit)
  → 계단표 행 { rps, 달성, p50, p95, 드롭%, 실패%, hit }
  + /admin/stats 델타 → node_load_share [0.629, 0.371]
  → 무릎 = 첫 위반(①드롭>1% ②p95>기저×10 ③실패≥0.1%) 직전 계단
```

---

## 6. 디자인 패턴

| 패턴 | 적용 위치 | 왜 사용했는가 | 구조 |
|------|----------|-------------|------|
| 통제 실험(대조군) | s=0 vs s=2 메모리 비교 | 수정 전 원인 고정 | 변인 1개(CDF 유무)만 다르게 |
| 런 분리(프로세스 격리) | 계단 실행 | VU 풀 해제 보장 | 계단 = 독립 docker run |
| 이중 장부 교차 검증 | 앱 카운터 vs k6 집계, miss ≡ origin | 조용히 틀린 숫자 방지 | 독립 경로 2개 대조 |

**패턴 상세:**

### 통제 실험 (대조군)
- **의도**: "고쳤다"는 믿음이 아니라 변인 하나의 인과를 실측으로 고정.
- **구조**: 동일 조건에서 CDF 만 제거(s=0)한 대조군.
- **이 프로젝트에서의 적용**:
```
s=2 (CDF 있음):  메모리 3.1 → 6.0 GiB, OOM kill (exit 137)
s=0 (CDF 없음):  메모리 ~570 MiB, 정상 완주
```
- 출처: task.md §3 진행 로그 (2026-07-19), scratchpad `dbg-stats.log`·`dbg0-stats.log`

---

## 7. 설정 / 컨벤션

| 항목 | 값 | 이유 |
|------|---|------|
| CPU 총량 (Phase 0/4) | 0.5 (A) / 0.25×2 (B·C) | BPC 무릎을 APC 천장(10.7k)의 70% 아래로 |
| 계단 길이 | 30s(Phase 0 판정) · 60s(Phase 4) | 20s 는 CFS 진동에 비단조 |
| k6 컨테이너 캡 | `-m 6g --memory-swap 6g` | APC 가용 램 ~9G(상주 6G 차감) 내 |
| 워밍업 | s=1.5 · 2,000 RPS · 60s · SEED=1 | 무릎(4,000+) 아래 안전 부하 — 단 시드 불일치 함정 확인(gate P2) |

---

## 8. 테스트에서 사용된 것들

### 테스트 프레임워크

| 라이브러리 | 버전 | 용도 |
|-----------|------|------|
| k6 스크립트 자체 검정 | — | `verify-zipf.js` 를 `--vus 1 --iterations 1` 로 실행 |

### 테스트 유틸리티 / 헬퍼 · Mock/Stub · 어노테이션 · 픽스처

해당 없음 — 검정은 실계산 대조(이론 top-10 몫 vs 표본)와 시퀀스 동등성 비교로만 구성.

### Assertion 메서드

| 메서드 | 소속 | 검증 내용 | 예시 |
|--------|------|----------|------|
| `throw new Error(...)` + allPass | verify-zipf.js | 하나라도 FAIL 이면 비정상 종료 | `if (!allPass) throw new Error('Zipf 생성기 검정 실패')` |

**대표 테스트 코드:**
```js
  // SharedArray 경로 ≡ 일반 배열 경로 (같은 시드 → 같은 시퀀스)
  const plain  = samplerFromCdf(buildCdf(SHARED_N, SHARED_S), SHARED_N, 11);
  const shared = samplerFromCdf(SHARED_CDF, SHARED_N, 11);
  let sharedSame = true;
  for (let i = 0; i < 100000; i++) if (plain() !== shared()) { sharedSame = false; break; }
  console.log(`  SharedArray ≡ 일반 배열 : ${sharedSame ? 'PASS' : 'FAIL'}`);
  if (!sharedSame) allPass = false;
```
- 출처: `k6/verify-zipf.js:49-55`

---

## 9. 새로 알게 된 것

- SharedArray 의 공유는 **저장의 공유**다 — 접근은 매번 원소 복사. "원소 크기 = 접근
  비용 = VU 메모리"라 데이터 배치가 곧 자원 설계다.
- preAllocatedVUs 는 시나리오 startTime 과 무관하게 **테스트 시작에 전부** 초기화된다.
- 같은 포화가 30s 창에선 드롭으로, 60s 창에선 지연으로 표현된다 — 판정 기준을 드롭에
  걸면 창 길이에 결론이 좌우된다.
- 해시 분할의 핫 노드 몫은 이론 상수가 아니라 해시×키 이름의 실측값(0.692 가정 vs
  0.628 실측 — P1 을 가른 차이).
- k6 exit 99 는 threshold 위반일 뿐 측정은 유효하다.
- 부하 생성기의 네트워크 경로(유선/무선)가 장비 스펙이다 — 코어 수보다 먼저 본다.

---

## 10. 더 공부할 것

| 주제 | 왜 공부해야 하는가 | 참고 자료 |
|------|-----------------|----------|
| CFS bandwidth control 의 스로틀 큐 동작 | 무릎의 미시 메커니즘(주기 내 정지)이 p50 점프를 만드는 정확한 형태 | kernel.org sched-bwc 문서 |
| k6 SharedArray 내부 구현(원소 직렬화 포맷) | 접근 비용의 상수를 정확히 알면 CPU 천장(10.7k)을 끌어올릴 여지 | grafana/k6 소스 `data/share.go` |
| B 의 무릎이 노드당 2,250(−10%)인 원인 | 미스 왕복의 스레드 점유 가설 미검증 — Phase 2(s=1)에서 미스율 커지면 재현될 것 | phase-0 gate P1 절 |
