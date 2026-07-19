# review-log: arm D — 레디스 외부화

## 루프 메타

- packet base SHA: `16d395e..작업트리` (arm D diff + phase-d 문서 3종)
- 입력 격리: Claude 워커 repo 읽기 전용(핵심 파일 포함 정독 + phase-0/4 교차 대조) /
  codex redact 패킷 — **1차 패킷에 untracked 신규 파일 2개가 누락**(C1, `git diff HEAD`
  는 untracked 미포함)되어 **보충 패스**로 그 2파일 전문 검토. 비대칭 사유: 패킷 결함
  (발견 즉시 보정).
- 리뷰 형태: 듀얼 1패스(中) + codex 보충 1회 — 회차: 1
- 종료 조건: open(채택·미수정)=0 ☑ / post-fix 재점검(빌드+수정 절 재독) clean ☑ /
  대칭 부담: 해당 없음(채택 finding 다수)

## 리뷰 모드

- codex 교차검증: 수행 ☑ (본 패스 + 보충 패스 — read-only·ephemeral)
- **Claude 워커(독립 서브에이전트)**: 수행 ☑ — LRU 동치·동시성 렌즈는 코드 정독으로,
  판정 논리는 phase-0/4 원표 재검산으로 검토
- 셀프리뷰: diff self-review (보조)

## verified

해당 없음 — 채택 finding 12건.

## finding ledger

| id | loop | source | 근거 | 요지 (1줄) | disposition | 채택/기각 근거 | status | fixed_in_loop |
|----|------|--------|------|-----------|------|------|------|------|
| W1/C2 | 1 | worker+codex | gate P4 | "전 계단 ±0.01" 등록을 "정상 구간"으로 사후 축소해 일치 판정 | 채택 | 1차 5000/6000 이 최대 0.026 이탈 | fixed(부분 일치로 재판정) | 1 |
| W2/C4 | 1 | worker+codex | gate 종합 | "per-CPU −3%"는 B=무릎·D=최대달성 혼합 계산 | 채택 | 일관 기준 재산출: 무릎 −17%·달성 −37% | fixed(분해 표 + 결론 재서술, README·TIMELINE·TECHNICAL 연쇄 수정) | 1 |
| W3 | 1 | worker | run_arm.py:24 | 커밋된 CPU_TOTAL=4.0 으론 문서의 0.5 배분이 재현 불가 | 채택 | "단일 출처" 주장과 모순 | fixed(env 오버라이드 + result.md 재현 명령) | 1 |
| W4/C3 | 1 | worker+codex | gate P3 | 병목 판정이 전 구간 통계 + 앱 max/redis p90 비대칭 | 채택 | 등록 문구는 "무릎 시점" | fixed(판정 런 3000 계단 창·동일 통계량 max 로 재산출 — 앱 93~95%·redis 68%, 판정 유지) | 1 |
| C1 | 1 | codex | 리뷰 패킷 | untracked 신규 파일 2개가 diff 패킷에 누락 | 채택 | git diff HEAD 는 untracked 제외 | fixed(보충 패스 실행) | 1 |
| C5 | 1 | codex | gate P2 | p50 차이를 RTT+Lua 구성요소로 분해 해석 — 분위수는 뺄셈 불가 | 채택 | 통계 비약 | fixed("종단간 p50 차이"로 한정) | 1 |
| C6 | 1 | codex | result.md | A/B/C 동일 규칙 재판정이 감사 가능 형태로 없음(spec 요구) | 채택 | 사전 등록 산출물 누락 | fixed(기저 p95·첫 위반 조건 표 추가) | 1 |
| C7 | 1 | codex | compose·run_arm | 메모리 총량 미고정(redis 256m 추가) — "같은 자원" 과장 | 채택 | 사실 | fixed(종합에서 "같은 CPU·캐시·스레드 예산"으로 한정 명시) | 1 |
| D1 | 1 | codex(보충) | RedisLruCache.resetCounters | in-flight get 과 비원자 — 무트래픽 전제 없으면 카운터 오염 | 채택(계약 문서화) | 러너 규약이 정지 보장 — 코드는 주석으로 계약 명시 | fixed(주석) | 1 |
| D2 | 1 | codex(보충) | RedisLruCache.clear | HLEN+DEL 비원자 — 반환값·삭제 대상 어긋남 가능 | 채택 | LruCache 와 비동치 | fixed(CLEAR_LUA 원자화) | 1 |
| D3 | 1 | codex(보충) | RedisLruCache | 커넥션·클라이언트 미종료 — 컨텍스트 재생성 시 누수 | 채택 | 자원 누수 | fixed(AutoCloseable.close — conn.close→client.shutdown) | 1 |
| D4 | 1 | codex(보충) | ZSET 점수 2^53 | 시퀀스가 2^53 넘으면 동률 발생 | 기각(수용) | 실험 수명상 도달 불가(10^15 요청) — 한계로만 인지 | noted | — |

## finding 상세 (핵심)

### W2/C4: per-CPU −3% 혼합 계산 (가장 아픈 지적)
- 지적: B 9,000(무릎 기준) vs D 8,700(최대 달성 기준) — 운전점이 다르다. 일관 기준이면
  무릎 −17% / 최대 달성 −37%.
- 영향: "동기 redis 호출의 앱 측 비용은 작다"는 종합 결론이 뒤집힘 → **"예산 세금
  −20% × 효율 세금 −17%"** 로 재서술 (gate·README·TIMELINE·TECHNICAL 4곳 연쇄 수정).
  무릎 산수도 재검산: 0.4 CPU × 7,500 = 3,000 = 실측 무릎 (오히려 더 정합).

### W4/C3: P3 통계량 비대칭
- 재산출: 판정 런(pdr2) 3000 계단 창에서 동일 통계량(max) — 앱 18.6/19.0%(쿼터
  93/95%) ≥90% ✓, redis 6.8%(68%) <70% ✓. **판정 방향은 유지되나 근거가 등록 문구
  그대로가 됐다.** 1차 초안의 70% 경계 max 는 붕괴 계단 포함 전 구간 통계였음.

### D2·D3 (코드 수정 — 측정 후 diff)
- CLEAR_LUA·close() 는 **측정에 쓰인 바이너리엔 없던 코드**다. 핫패스(get/put Lua·
  카운터)는 무변경이므로 측정 결과 유효 — 재측정 불요 판단. 빌드·기존 테스트 통과,
  BPC 재배포 완료.

## 잔여 리스크 / 사용자 결정 필요

- D 의 과부하 goodput 역행 메커니즘 미분리 (gate 미해결 ①) — 다음 태스크 후보.
- 사전 등록 문서(spec) 커밋이 여전히 훅 승인 대기 — "약한 증거" 상태 지속.
