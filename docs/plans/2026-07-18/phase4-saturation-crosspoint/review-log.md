# review-log: Phase 0·4 재개 — k6 수정 + 측정 판정 문서

## 루프 메타

- packet base SHA: `f8d037f..작업트리` (미커밋 diff — k6/ 4파일 + phase-0·4 문서 6종)
- 입력 격리: Opus(Claude) 워커 packet-only ☑ (repo 읽기 전용, 검토 대상·렌즈 지정) /
  codex packet-only ☑ (redact 패킷 단일 파일 — 내부 IP 치환: 192.168.55.x → *_HOST) /
  비대칭 입력 사유: codex 는 redact 패킷이라 raw 실행 불가(스스로 명시), 워커는 k6 실행 검증 수행
- 리뷰 형태: 듀얼 1패스(中) — 회차: 1
- 종료 조건: open(채택·미수정)=0 ☑ AND 신규 채택=0 (post-fix 재점검에서) ☑ AND
  대칭 부담: 해당 없음(신규 채택 finding 8건 > 0) / post-fix 타깃 재점검 clean ☑
  (verify-zipf 전 항목 PASS 재실행 + 수정 절 재독)

## 리뷰 모드

- codex 교차검증: 수행 ☑ (1회 — `codex exec` read-only·ephemeral, 출력 codex-out.md)
- **Opus(Claude) 워커(독립 서브에이전트) 리뷰**: 수행 ☑ — general-purpose 에이전트,
  자체적으로 k6 v2.0.0-rc1 로 빈 SharedArray 경로·verify-zipf 실행 검증까지 수행
- 셀프리뷰: diff self-review (보조)

## verified (대칭 부담)

해당 없음 — 신규 채택 finding 8건 (검사의 유효성이 finding 으로 입증됨).

## finding ledger

| id | loop | source | 근거 | 요지 (1줄) | disposition | 채택/기각 근거 | status | fixed_in_loop |
|----|------|--------|------|-----------|------|------|------|------|
| C1 | 1 | codex | phase-0 spec §3.1 vs phase-4 문서 | 계단 20s 등록 ↔ 30s 실행의 충돌 미명시 | 채택 | 사실 — 사전등록 이탈 은폐 위험 | fixed | 1 |
| C2 | 1 | codex | phase-4 gate P1 | 드롭 소멸을 창 길이 탓 단정 — 반복 없는 인과 비약 | 채택 | k6 드롭 메커니즘상 다요인 | fixed | 1 |
| C3 | 1 | codex | phase-4 gate 종합 표 | 5000 행만 p50 사용 — 지표 사후 교체 | 채택 | 등록 지표는 p95 단일 | fixed | 1 |
| C4 | 1 | codex | phase-0 gate P1 | "닫혔다" 주장 vs B 2,250·C 2,512 (12%) — R_node 동일 가정 미입증 | 채택 | 표 자체가 반례 | fixed | 1 |
| C5 | 1 | codex | phase-4 gate P4 | CFS 조각 크기 인과 — 교란 요인 미분리 | 채택 | 대조 arm 부재 | fixed | 1 |
| W1 | 1 | opus-worker | k6/zipf.js·load.js:52·probe.js | 빈 SharedArray 오배선 시 침묵 퇴화(전 요청 인덱스 0) — 검정 사각 | 채택 | 실행 검증 포함, 침묵 실패 클래스 | fixed | 1 |
| W2 | 1 | opus-worker | phase-0 gate P2 | 20s 런이면 P2 빗나감(0.80) — 런 선택이 fail→pass 뒤집음, 미공개 | 채택 | result.md run1 수치로 확인 | fixed | 1 |
| W3 | 1 | opus-worker | phase-4 gate P2 | 인용 숫자 2건 오류(최대 0.40→0.50%p·"±0.1%p 평평") | 채택 | result.md 표 대조로 확인 | fixed | 1 |

## finding 상세 (채택)

### C1/W2 (동일 뿌리): 20s→30s 프로토콜 변경의 취급
- 출처·렌즈: codex·판정 논리 ∥ 워커·판정 논리 (독립적으로 같은 뿌리를 다른 각도에서)
- 지적 요지: spec 은 20s 등록, 판정은 30s 런 — C1 은 문서 간 충돌, W2 는 P2 판정이 런
  선택에 뒤집힌다는 실질 효과까지.
- 판정: 채택 — run1(20s) B 무릎 4000 → P2 비율 0.80(밴드 밖).
- 수정: phase-0 gate 에 "방법 이탈 명시" 절 신설 + P2 를 "조건부 일치(런 민감)"로 재판정.
  P1 은 두 런 모두 밴드 밖이라 판정 불변임을 병기.

### W1: samplerFromCdf 침묵 퇴화 가드
- 출처·렌즈: 워커·테스트 정합성 (k6 실행으로 실패 모드 재현 분석)
- 지적 요지: `SKEW === 0 ? null : CDF` 삼항이 두 파일에 중복 — 깨지면 빈 SharedArray 가
  cdf 로 들어가 전 요청이 key-0 수렴, 에러 없음.
- 판정: 채택 — "조용히 틀린 숫자"가 이 프로젝트의 지배 실패모드.
- 수정: `cdf.length !== n` 즉시 throw (zipf.js, changelog J-4). verify-zipf 전 항목 PASS 재확인.

### C3: 지표 사후 교체
- 판정: 채택 — 종합 표를 "탐색적"으로 명시, 100% 행을 "축이 갈림"으로 재서술, 다음 스윕에
  복합 판정 규칙 사전 등록 요구를 남김.

### C2·C4·C5: 인과·주장 강도 완화 (각각 드롭 창길이·R_node 가정·CFS 조각)
- 판정: 채택 — 관측과 가설을 분리해 재서술. 데이터가 지지하는 최소 주장으로 축소.

### W3: 인용 숫자 교정
- 판정: 채택 — 0.50%p(4500)로 정정, B 의 0.34%p 폭을 명시(판정 결과는 불변).

## 참고 (기각 아님 — 리뷰어 간 정보)

- 워커: J-3 의 "JSON float 왕복 흔들림" 우려는 무근(ES Number 직렬화는 최단 왕복 표현) —
  동등성 검정은 "원소 래핑 회귀" 감지용으로 유효하므로 유지. changelog J-3 의 당시
  근거 서술은 결정 시점 기록으로 보존.
- codex: 코드 diff 4렌즈(정확성·자원·경계값·테스트) 전부 문제 없음 — 워커도 실행 검증으로 동의.

## 잔여 리스크 / 사용자 결정 필요

- P2(phase-0)의 확정은 30s 프로토콜 사전 등록 반복 측정이 필요 — Phase 2(s=1) 스윕 때 병행 권장.
- 커밋·push 는 git-guard 승인 대기(사용자 확인 필요).
