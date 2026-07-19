# changelog: Phase 0·4 재개 — SharedArray 2차 결함 수정 + 포화·부하 측정

**검증 상태**: 통과 — ① 메모리: 동일 조건(s=2·RPS 5000·VU 1,250) 6GB OOM → 464MB 완주
② `verify-zipf.js` 전 항목 PASS (SharedArray ≡ 일반 배열 동일 시퀀스 포함) ③ Phase 0·4
전 계단 sanity(miss 합 ≡ origin 증가분) 일치.

커버리지: 변경 파일 = `k6/zipf.js`(J-1·주석) · `k6/load.js`(J-1) · `k6/probe.js`(J-1·J-2) ·
`k6/verify-zipf.js`(J-3). 셀프체크 ☑ (docs/·results/ 는 측정 산출물, 프로세스 문서 제외 규칙).

## 1. 판단 항목 (J)

### J-1: CDF SharedArray 를 "원소 1개 래핑" → "원소 40k 개 + s=0 은 null 전달"로 — `k6/load.js:48-52`, `k6/probe.js:34-35`, `k6/zipf.js:66-79`(주석)

- **왜**: 직전 커밋(f8d037f)의 수정이 불완전했다. SharedArray 는 **원소 접근 시 그 원소를
  역직렬화해 VU 로 복사**한다 — 전체 CDF 를 원소 1개로 감싸면 `CDF[0]` 접근 순간 VU 마다
  40k 배열 사본(~5MB)이 생겨 공유가 무효화된다. VU 1,250 × 5MB ≈ 6GB → 컨테이너 OOM,
  전날엔 APC 호스트 동결(TIMELINE 문제 ⑥).
- **대안 비교**:
  | 접근 | 장점 | 단점 | 선택/기각 사유 |
  |------|------|------|---------------|
  | 원소 40k 개 SharedArray (선택) | 접근당 스칼라 1개만 복사 — VU 메모리 상수 | 이분탐색 1회당 ~17회 역직렬화 | CPU 비용 실측 무시 가능(464MB·5k RPS 완주), 의미 불변 |
  | VU 마다 plain 배열 재구축 | 접근 비용 0 | VU 당 ~5MB — 원래 결함 ① | 기각: 두 번 데인 바로 그 모양 |
  | 역CDF 양자화 테이블 | 접근 1회로 단축 | 분포 근사 오차 — Zipf 정확성 훼손 | 기각: "이 파일이 틀리면 모든 숫자가 틀린다" |
- **근거 출처**: 통제 실험(s=0 대조 570MB vs s=2 6GB — task.md §3 로그) + k6 SharedArray 동작.
- **코드** (`k6/load.js:48-52`):
  ```js
  // ★ CDF 는 원소 40k 개짜리 SharedArray — 원소 1개로 감싸면 VU 마다 전체가
  //   복사돼 부하 생성기가 죽는다 (zipf.js 경고·TIMELINE.md 문제 ⑥).
  const CDF = new SharedArray('cdf', () => buildCdf(N, SKEW) || []);
  // VU 마다 자기 시드 → 재현 가능. 전체 합은 Zipf.
  const sampler = samplerFromCdf(SKEW === 0 ? null : CDF, N, SEED + __VU);
  ```
  | 줄 | 근거 해설 |
  |----|----------|
  | L50 | `buildCdf` 는 s=0 에서 null 반환 — SharedArray 콜백은 배열이 필수라 `\|\| []` 로 빈 배열 |
  | L52 | s=0 이면 sampler 에 null 을 넘겨 균등 경로(이분탐색 생략) 유지 — `CDF.length` 검사 대신 SKEW 직접 분기(의도 명시) |
- **리뷰 연습 포인트**:
  - 자원 렌즈 — VU 수 × 원소 접근당 복사량, 이 곱을 코드에서 어디가 상한 짓나?
  - 경계값 렌즈 — s=0(빈 SharedArray)·N=1·u=1.0 근처에서 이분탐색이 안전한가?

### J-2: probe.js VU 과할당 축소 (시나리오당 3,000 → rps/50·상한 500, maxVUs 6,000→1,500) — `k6/probe.js:19-40`

- **왜**: preAllocatedVUs 는 **시나리오 수만큼 곱해져 초기화 단계에 전부** 생성된다 —
  계단 5개 × 3,000 = 15,000 VU. CDF 를 고쳐도 VU 런타임 자체(~0.45MB)로 ~7GB. VU 수요는
  rate × 지연이므로 지연 20ms 가정 rps/50 이면 충분하고, 포화 시엔 maxVUs 도달 →
  dropped_iterations 가 바로 찾는 신호다. 포화 측정의 기본 경로는 런 분리(spec §3.1)로
  바꾸고 이 파일은 안전한 예비로만 남긴다.
- **대안 비교**: 파일 삭제(기각 — 단일 런 계단이 유효한 사용처가 남음: 시나리오 2~3개
  저부하 탐색) / 축소+주석(선택). 검토 없이 자명했으면에 해당 안 함.
- **근거 출처**: 2026-07-18 APC 동결 사고 + VU 메모리 실측(s=0 대조군 570MB/1,250 VU).
- **코드** (`k6/probe.js:33-40` 일부):
  ```js
  STEPS.forEach((rps, i) => {
    scenarios[`s${rps}`] = {
      executor: 'constant-arrival-rate',
      rate: rps, timeUnit: '1s', duration: `${STEP_S}s`,
      preAllocatedVUs: Math.min(500, Math.max(50, Math.ceil(rps / 50))),
      maxVUs: 1500,
  ```
  | 줄 | 근거 해설 |
  |----|----------|
  | preAllocatedVUs | 지연 20ms 가정의 VU 수요 + 하한 50(시작 버스트)·상한 500(시나리오 곱 안전) |
  | maxVUs 1500 | 5 시나리오 최악 7,500 VU × 0.45MB ≈ 3.4GB — 6g 캡 안 |
- **리뷰 연습 포인트**: 자원 렌즈 — maxVUs 가 전 시나리오 동시 활성일 때의 곱을 계산해봤나?

### J-3: verify-zipf 에 SharedArray 경로 동등성 검정 추가 — `k6/verify-zipf.js:5-14,49-56`

- **왜**: 기존 검정은 legacy `zipfSampler`(plain 배열)만 검사 — 이번에 바뀐 표면(SharedArray
  경유 이분탐색)을 아무도 안 봤다. JSON 왕복으로 float 가 흔들리면 이분탐색 결과가 달라질
  수 있어(문제 ⑥의 회귀 방어) 같은 시드로 두 경로의 시퀀스 완전 일치를 검정한다.
- **대안 비교**: 대안 검토 없음(자명: 바뀐 코드 경로는 바뀐 검정이 필요 — 기존 검정 구조에 1항목 추가가 최소 diff).
- **근거 출처**: spec §3.1 규약 수립 중 결정 (task.md §3 로그).
- **코드** (`k6/verify-zipf.js:49-55`):
  ```js
  // SharedArray 경로 ≡ 일반 배열 경로 (같은 시드 → 같은 시퀀스)
  const plain  = samplerFromCdf(buildCdf(SHARED_N, SHARED_S), SHARED_N, 11);
  const shared = samplerFromCdf(SHARED_CDF, SHARED_N, 11);
  let sharedSame = true;
  for (let i = 0; i < 100000; i++) if (plain() !== shared()) { sharedSame = false; break; }
  console.log(`  SharedArray ≡ 일반 배열 : ${sharedSame ? 'PASS' : 'FAIL'}`);
  if (!sharedSame) allPass = false;
  ```
  | 줄 | 근거 해설 |
  |----|----------|
  | L50-51 | 동일 시드(11)·동일 N·s — 차이는 오직 저장 경로. 100k 표본이면 이분탐색 전 구간 커버 |
  | L55 | allPass 에 합류 — 실패 시 기존 검정과 같은 예외 경로로 강제 |
- **리뷰 연습 포인트**: 테스트 정합성 렌즈 — 이 검정이 실패할 수 있는 실제 조건(float 왕복 손실)을 재현할 수 있나, 아니면 항상 통과하는 검정인가?

### J-4: samplerFromCdf 에 CDF 길이 가드 — `k6/zipf.js:81-84` (리뷰 finding W1 반영)

- **왜**: "s=0 은 빈 SharedArray + null 전달" 규약의 삼항이 load.js·probe.js 에 중복으로
  산다 — 한쪽이 깨져 빈 SharedArray 가 cdf 로 들어오면 이분탐색의 `cdf[mid] < u` 가 항상
  false 라 **전 요청이 인덱스 0 으로 수렴하는데 에러가 없다**(그럴듯한 ~100% 히트율이
  나오는 침묵 결함). 길이 불일치를 즉시 throw 로 바꾼다.
- **대안 비교**: verify-zipf 에 배선 검정 추가(기각 — 검정은 load.js/probe.js 의 실제
  배선을 못 탄다, 런타임 가드가 클래스 전체를 막음) / 가드(선택).
- **근거 출처**: 리뷰 finding W1 (review-log.md — 독립 Claude 워커, k6 실행 검증 포함).
- **코드** (`k6/zipf.js:79-84`):
  ```js
  export function samplerFromCdf(cdf, n, seed) {
    const rand = rng(seed);
    if (cdf === null) return () => Math.floor(rand() * n);   // 균등
    // 빈 SharedArray(s=0 용)가 cdf 로 잘못 들어오면 이분탐색이 조용히 전부
    // 인덱스 0 으로 수렴한다 — 에러 없이 그럴듯한 숫자가 나오는 최악 모드. 즉시 죽인다.
    if (cdf.length !== n) throw new Error(`CDF 길이 ${cdf.length} ≠ N ${n} — s=0 은 null 을 넘겨라`);
  ```
  | 줄 | 근거 해설 |
  |----|----------|
  | 길이 비교 | 빈 배열(0)뿐 아니라 N 불일치 전반을 잡음 — KEYSPACE env 오배선도 커버 |
- **리뷰 연습 포인트**: 침묵 실패 렌즈 — 이 코드가 틀렸을 때 "에러"가 나는가 "그럴듯한 숫자"가 나는가?

## 2. 기계적 변경 (M)

- 없음.

## 3. 생성물 (G)

- `results/phase-0-saturation/run1·run2/*`, `results/phase-4-load/*` — 측정 raw (원인: 이 작업의 측정 자체, J 아님).
