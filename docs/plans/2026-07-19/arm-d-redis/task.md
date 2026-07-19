# task — arm D: 레디스 외부화 (공유 Redis LRU + 라운드로빈)

> 2026-07-19. 입력: `docs/TODO.md` 3번. 모드: auto-implements (사용자 지시 — "레디스랑
> 코드 작업 전부 하고 … 실험도 이어서 진행하고 전체 결과 추출", "이제 알아서 진행").
> CPU 배분 결정: **총량 고정에 redis 포함** (사용자: "CPU 고정이 맞지").

## 1. 정의 (명확도 6칸)

| # | 칸 | 내용 |
|---|----|------|
| 1 | 목표·대상 | arm D(2 앱 노드 RR + 공유 Redis, Lua ZSET+HASH 로 **엔트리 수 정확 LRU**)를 구현·배포하고, Phase 0·4 와 동일 리그(s=1.5·N=40k·CPU 총량 0.5)에서 지연·포화점을 실측해 A/B/C 와 비교 — "외부화의 대가"가 결과 문서로 기록되면 끝 |
| 2 | 경계·불변식 | ① 캐시 총량 10,000 엔트리 정확(바이트·근사 아님 — Redis maxmemory LRU 미사용) ② CPU 총량 0.5 고정: **앱 0.2×2 + redis 0.1** (redis 는 캐시 계층이므로 총량 안에서 계상) ③ 스레드 총량 200(100×2) ④ 기존 arm A/B/C 동작 불변(mode 기본값 local) ⑤ miss 합 ≡ origin 증가분 ⑥ LRU 시맨틱 = LruCache 와 동일(get 이 순서 갱신, 초과 시 최소 사용 축출) |
| 3 | 기준소스 | `docs/TODO.md` 3번 · `LruCache.kt`(시맨틱 기준) · Phase 0/4 결과(비교 기준선) · 연역: D 히트율 ≡ A(캐시 하나 10k) |
| 4 | 금지영역 | LruCache 시맨틱 변경 · 기존 arm.env/upstream(a·b·c) · phase-0/1/4 결과 문서 · Redis 기본 maxmemory-policy(allkeys-lru 등 — 근사라 금지) |
| 5 | 검증 방법 | ① 빌드+기존 테스트(LruCacheTest) ② Redis Lua LRU 단위 검증(용량 3 시나리오 — 축출 순서·get 승격) ③ 연역 대조: D 정상상태 히트율 ≡ A(±0.3%p) ④ 계단·그리드 sanity(miss≡origin) ⑤ 듀얼 리뷰(중간 stakes) |
| 6 | stakes | **중간** — 신규 코드 + 측정, EXPERIMENTS §0 하한(조용히 틀린 숫자) 유지 |

### 트리아지 (dimensions.md 14차원)

- 활성: **#6 예외**(redis IO 실패 경로 신설 — 연결 실패 시 기동 실패가 의도, 요청 중 실패는 500 전파(나이브 앱 의도) — 낮음↑) · **#8 성능**(요청당 redis 왕복 추가 — 그 자체가 측정 대상 — 낮음↑) · **#16 비용**(lettuce 의존성 + redis 인프라 도입 — 중간(인프라 추가), 자동연동 #11 light)
- light: **#4 데이터 정합성**(공유 캐시 write — Lua 스크립트 원자성으로 방어, 검증 ②로 비활성 전환 예정) · **#11 보안**(연동 — 내부 랩 네트워크·인증 없는 redis 는 컨테이너 네트워크 안, 포트 노출은 검증용 6379 — 랩 전용으로 수용, 비활성 전환 예정)
- 비활성: #2(외부 입력 없음 — 기존 /key 경로 불변, 증거 KeyController diff 없음 수준) · #3(인증 표면 없음) · #5(스탬피드는 의도된 기존 동작 — DECISIONS 빚 D) · #9(redis 는 측정 대상이지 복구 대상 아님 — 실험 실패로 드러남) · #10(운영 없음) · #12(API 계약 불변 — 응답 스키마 동일) · #14(도메인 규칙 없음) · #15(스키마 없음) · #17(응답 동일)
- stakes 도출: 하한 중간(#16) = EXPERIMENTS 선언과 일치.

## 2. 계획

- 변경 파일: `app/.../cache/KeyCache.kt`(신규 인터페이스) · `LruCache.kt`(인터페이스 구현만) · `RedisLruCache.kt`(신규 — Lettuce + Lua) · `CacheConfig.kt`(mode 분기) · `KeyController.kt`·`AdminController.kt`(타입만 KeyCache 로) · `AppProperties.kt`·`application.yml`(mode·redis.uri 추가, 기본 local) · `app/build.gradle.kts`(lettuce) · `arms/compose.yml`(redis 서비스, profile) · `arms/d-redis/arm.env`(신규) · `nginx/upstream/arm-d.conf`(신규 RR) · `scripts/run_arm.py`(d-redis 등록)
- 변경하지 않을 파일: origin·k6·기존 upstream·기존 arm.env·phase 문서.
- 순서: 코드 → 로컬 빌드·테스트 → 배포(deploy.sh) → Lua LRU 검증 → **phase-d spec 사전 등록** → 저부하·계단·그리드 측정 → result/gate → 4종+review 문서.
- 검증 명령: `./gradlew build` · redis-cli 시나리오 · 계단 러너(스크래치 p0-arm 계열 재사용).

## 3. 진행 로그

- 시작: 소스 구조 파악(LruCache 락 시맨틱·컨트롤러 의존·compose x-app 구조 확인).
- 구현: KeyCache 인터페이스 추출(LruCache 시맨틱 불변 — override 만 추가) → RedisLruCache
  (Lettuce 단일 공유 커넥션 + Lua 2본: GET=HGET+ZADD(INCR) / PUT=HSET+ZADD+HLEN 초과 시
  ZPOPMIN→HDEL 루프) → CacheConfig mode 분기(오타는 기동 실패) → compose redis 서비스
  (profile=redis·persistence off·`required: false` 로 A/B/C 무영향) → arm-d.conf(RR, B 동일)
  → run_arm.py d-redis 등록(shared: 캐시 총량 그대로·REDIS_FRAC=0.2).
- 빌드·기존 테스트 통과. deploy.sh 로 BPC/APC 배포.
- 스모크: 노드1 미스 → **노드2 히트**(공유 캐시 증명), 카운터 노드별 정상. (nginx 기동
  직후 일시 빈 응답 1회 — 재현 안 됨, upstream 워밍업으로 판단.)
- **Lua LRU 정확성 게이트 통과**: 용량 3 시나리오 — put×3 후 get(k1) 승격 → put(k4) 가
  k2 축출(k1,k3,k4) → get(k2) 미스 → put(k5) 가 k3 축출(k1,k4,k5). size 3 유지.
- phase-d spec.md **측정 전 사전 등록** — 선행 페이즈 절차 결함 3건 반영(30s 명시·복합
  판정 규칙(p50>5ms 추가)·측정 시드 워밍업). 새 복합 규칙을 A/B/C run2 에 재적용해도
  무릎 불변(5000/4500/4000) — 비교 잣대 통일 확인.
- 계단(2000~6000×30s) + 60s 확인 지점(4000/4500/5000) 완료. 1차 계단 저부하 2계단이
  과도 상태 → 정상상태 재계단(pdr2, 기준 불변·병기). **무릎 3000, 저부하 p50 0.42ms,
  병목=앱 CPU(무릎 창 93~95%/쿼터, redis 68%), P1·P3 일치·P2 좋은 쪽 빗나감·P4 부분.**
- result/gate 작성 → **듀얼 리뷰**(Claude 워커 ∥ codex 본+보충 패스): finding 12건 —
  채택 11(판정 4·프로세스 2·문서 2·코드 3)·수용 1. 핵심: "per-CPU −3%"가 운전점 혼합
  오계산(정정: 무릎 기준 −17% → 결론 재서술), P4 사후 축소 → 부분 일치 재판정, P3
  통계량 대칭 재산출(판정 유지), clear 원자화(CLEAR_LUA)·AutoCloseable·resetCounters
  계약 주석, run_arm CPU_TOTAL env 오버라이드, A/B/C 재판정 감사 표. 상세 review-log.md.
- 코드 수정(D2·D3)은 측정 후 diff — 핫패스 무변경이라 재측정 불요 판단, 빌드·테스트
  통과 후 BPC 재배포. 트리아지 light 2건(#4 데이터 정합성·#11 보안) 재판정: #4 는
  Lua 원자성 검증·C6 감사로 **비활성 전환**, #11 은 내부 랩 한정 수용으로 **비활성
  전환** — 칸6 stakes 중간 유지(#16 하한).
- **판별 런 (미해결 ① 해소, 사용자 지시)**: 4000 RPS × 180s 연속 — 달성 1,231·실패
  67.3%·p50 4.8s. 스텝말 아티팩트 가설 **기각**: goodput 역행은 실체·진행성(노출 길수록
  악화), admin 경로까지 200s 무응답(스레드 전원 고갈 직접 증거). gate 판별 런 절·README·
  TIMELINE 반영. 잔여: ~5s 절단 계층 특정(로그 보존 재현 필요 — 백로그).
- **종결.** 산출물: phases/phase-d-redis 3종 · 4종 문서 + review-log · README(실험군 D
  행·Phase D 절·상태 표) · TIMELINE §15 · EXPERIMENTS D 행 · TODO 갱신 · 측정 로그 1행.
  잔여: 커밋·push 승인 대기(전 세션분 포함), goodput 역행·REDIS_FRAC 감도·arm E 는
  다음 태스크 후보.
