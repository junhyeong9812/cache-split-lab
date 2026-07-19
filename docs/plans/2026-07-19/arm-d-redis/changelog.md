# changelog: arm D — 레디스 외부화 구현 + 실측

**검증 상태**: 통과 — ① `./gradlew build`(기존 LruCacheTest 포함) ② 스모크(노드1 미스 →
노드2 히트 = 공유 캐시) ③ Lua LRU 용량 3 시나리오(축출 순서·get 승격·size 유지)
④ 측정 sanity(miss ≡ origin, 전 계단) ⑤ P1 연역 대조(히트율 ≡ A ±0.07%p).

커버리지: 변경 파일 = `KeyCache.kt`(J-1) · `LruCache.kt`(J-1) · `RedisLruCache.kt`(J-2) ·
`CacheConfig.kt`(J-3) · `AppProperties.kt`·`application.yml`(J-3) · `app/build.gradle.kts`(J-2)
· `arms/compose.yml`(J-4) · `arms/d-redis/arm.env`(J-4) · `nginx/upstream/arm-d.conf`(J-4) ·
`scripts/run_arm.py`(J-5). 셀프체크 ☑.

## 1. 판단 항목 (J)

### J-1: KeyCache 인터페이스 추출 — `app/.../cache/KeyCache.kt`, `LruCache.kt:31`

- **왜**: 컨트롤러가 구체 클래스(LruCache)에 묶여 있어 캐시 배치를 바꾸려면 코드가
  갈라진다. "arm 간 차이는 배치(설정)뿐" 불변식을 지키려면 계약을 인터페이스로 올리고
  구현 선택을 설정으로 내려야 한다. LruCache 는 시그니처에 override 만 붙었다(시맨틱
  불변 — 금지영역).
- **대안 비교**:
  | 접근 | 장점 | 단점 | 선택/기각 사유 |
  |------|------|------|---------------|
  | 인터페이스 추출 (선택) | 기존 arm 무영향·계약 명시 | 파일 1개 추가 | 최소 diff 로 확장점 확보 |
  | RedisLruCache 가 LruCache 상속 | 파일 수 최소 | 인메모리 map·락을 물려받아 죽은 상태 보유 | 기각: 상속으로 구현 재사용은 왜곡 |
- **근거 출처**: task.md §2 계획.
- **코드** (`app/src/main/kotlin/dev/jun/cachesplit/app/cache/KeyCache.kt:16-33` 요지):
  ```kotlin
  interface KeyCache {
      val capacity: Int
      fun get(key: String): Long?
      fun put(key: String, value: Long)
      fun stats(): CacheStats
      fun keys(): List<String>
      fun resetCounters()
      fun clear(): Int
  }
  ```
  | 줄 | 근거 해설 |
  |----|----------|
  | 계약 주석 | LRU 시맨틱·엔트리 정확·카운터는 get 에서만 — 두 구현의 동치 조건을 문서로 고정 |
- **리뷰 연습 포인트**: 계약 렌즈 — 이 인터페이스만 보고 두 구현이 히트율에서 동치라고 믿을 수 있나? 무엇이 문서에만 있고 타입에 없나?

### J-2: RedisLruCache — Lua 2본으로 엔트리 정확 LRU — `app/.../cache/RedisLruCache.kt`

- **왜**: Redis maxmemory-policy 는 바이트 기준 + 샘플링 근사 — "총 캐시 = 10,000 엔트리
  정확" 불변식과 이론 대조가 깨진다(Caffeine 기각과 동일 논리, DECISIONS §10). HASH(값)
  + ZSET(순서) + INCR 카운터를 Lua 로 묶으면 Redis 단일 스레드가 LruCache 의 락 역할을
  한다. 접근 순서는 시계가 아니라 **단조 카운터**(같은 ms 동점 → 사전순 오염 방지).
- **대안 비교**:
  | 접근 | 장점 | 단점 | 선택/기각 사유 |
  |------|------|------|---------------|
  | Lua ZSET+HASH (선택) | 엔트리 정확·원자적·시맨틱 일치 | 요청당 Lua 실행 | 이론 유지가 실험의 전제 |
  | maxmemory allkeys-lru | 설정 한 줄 | 바이트 기준·근사 LRU | 기각: 재는 대상이 바뀜 |
  | 앱에서 GET/SET+수동 축출 | Lua 불필요 | 다중 명령 비원자 — 동시성 깨짐 | 기각: 침묵 오염 |
- **근거 출처**: TODO 3번(사전 결정) + spec §1.
- **코드** (`RedisLruCache.kt:66-79` PUT Lua):
  ```lua
  redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
  redis.call('ZADD', KEYS[2], redis.call('INCR', KEYS[3]), ARGV[1])
  local evicted = 0
  while redis.call('HLEN', KEYS[1]) > tonumber(ARGV[3]) do
    local old = redis.call('ZPOPMIN', KEYS[2])
    if old[1] == nil then break end
    redis.call('HDEL', KEYS[1], old[1])
    evicted = evicted + 1
  end
  return evicted
  ```
  | 줄 | 근거 해설 |
  |----|----------|
  | while HLEN | if 가 아닌 while — 어떤 경로로든 초과가 누적됐어도 수렴 보장 |
  | ZPOPMIN | 점수(카운터) 최소 = 최소 최근 사용 — LinkedHashMap eldest 와 등가 |
  | old[1]==nil break | ZSET·HASH 불일치(이론상 불가) 시 무한루프 방지 |
  단일 공유 커넥션(풀 없음)은 의도 — "나이브한 외부화"가 arm 정의고, Lettuce 파이프라인이
  직렬화 지점을 그대로 드러낸다. lettuce-core 의존성은 Boot BOM 버전 관리.
- **리뷰 연습 포인트**: 동시성 렌즈 — GET 스크립트와 PUT 스크립트 사이(앱의 미스 → origin → put 구간)에 끼어드는 다른 노드의 축출이 정합성을 깨는 경우가 있나? / 침묵 실패 렌즈 — evalsha 의 NOSCRIPT(redis 재시작) 때 무슨 일이 나나?

### J-3: CACHE_MODE 설정 분기 (오타 = 기동 실패) — `CacheConfig.kt`, `AppProperties.kt`, `application.yml`

- **왜**: 모드가 조용히 local 로 빠지면 "arm D 라고 믿고 딴 것을 재는" 침묵 오염 —
  이 프로젝트의 지배 실패모드다. when 의 else 를 error 로 막았다. 기본값 local 이라
  기존 arm A/B/C 는 diff 영향 0.
- **대안 비교**: 대안 검토 없음(자명: 조용한 기본값 금지는 리포 전반의 기존 규약 —
  compose `:?` 가드·capacity 기본값 없음과 동일 패턴).
- **근거 출처**: 기존 코드 패턴 (application.yml "미설정 시 기동 실패가 의도다").
- **코드** (`CacheConfig.kt:21-26`):
  ```kotlin
  @Bean
  fun keyCache(props: AppProperties): KeyCache = when (props.cache.mode) {
      "local" -> LruCache(props.cache.capacity)
      "redis" -> RedisLruCache(props.cache.capacity, props.redis.uri)
      else -> error("알 수 없는 CACHE_MODE: ${props.cache.mode} (local | redis)")
  }
  ```
  | 줄 | 근거 해설 |
  |----|----------|
  | else error | 오타를 기동 실패로 — 조용한 폴백 금지 |
- **리뷰 연습 포인트**: 설정 렌즈 — mode=redis 인데 REDIS_URI 오타면 언제 어떻게 죽나(기동? 첫 요청?).

### J-4: compose redis 서비스 + arm D 구성 — `arms/compose.yml`, `arms/d-redis/arm.env`, `nginx/upstream/arm-d.conf`

- **왜**: 단일 compose 불변식 유지 — redis 는 profile 로만 추가되고, A/B/C 는
  `depends_on.redis.required: false` + `CACHE_MODE` 기본값으로 **재빌드 없이 무영향**.
  redis 는 persistence 전부 off(fork·디스크 I/O 는 측정 노이즈), cpus 는 총량 고정의
  일부(`REDIS_CPUS`). arm-d.conf 는 arm-b 와 동일 RR — "캐시 위치만 변수" 유지.
- **대안 비교**: compose 파일 분리(기각 — "파일이 하나면 구조적으로 갈라질 수 없다"는
  기존 결정 위반) / profile 추가(선택).
- **근거 출처**: compose.yml 헤더의 기존 설계 결정.
- **코드** (`arms/compose.yml` redis 서비스):
  ```yaml
  redis:
    image: redis:7-alpine
    command: ["redis-server", "--save", "", "--appendonly", "no"]
    cpus: ${REDIS_CPUS:-0.1}
    mem_limit: ${REDIS_MEM:-256m}
    ports:
      - "6379:6379"
    profiles: ["redis"]
  ```
  | 줄 | 근거 해설 |
  |----|----------|
  | --save "" --appendonly no | RDB fork·AOF fsync 는 지연 튐 — 측정 대상이 아님 |
  | profiles | A/B/C 에선 서비스 자체가 안 뜬다 |
- **리뷰 연습 포인트**: 불변식 렌즈 — `required: false` 를 지원 안 하는 구버전 compose 에서 이 파일은 어떻게 실패하나(조용히? 시끄럽게?).

### J-5: run_arm.py d-redis 등록 (shared 캐시·REDIS_FRAC) — `scripts/run_arm.py`

- **왜**: 총량→노드당 계산의 단일 출처 유지. arm D 는 캐시를 **나누지 않고**(공유 1개
  = 총량) CPU 만 앱 (1-0.2)/2 + redis 0.2 로 나눈다. REDIS_FRAC=0.2 는 "redis 의 일은
  요청당 Lua 1회뿐"이라는 추정 — 실측(무릎에서 redis 는 쿼터의 ~70%)이 사후 근거.
- **대안 비교**: 러너 밖 수동 구성만(기각 — 불변식 단일 출처가 깨져 언젠가 한쪽만 수정됨).
- **근거 출처**: task.md 칸2 (사용자 결정 "CPU 고정") + run_arm.py 헤더 설계.
- **코드** (`scripts/run_arm.py:75-99` per_node 요지):
  ```python
  env = {
      "APP_CACHE_CAPACITY": CACHE_TOTAL if shared else CACHE_TOTAL // n,
      "APP_CPUS":           f"{CPU_TOTAL * (1 - REDIS_FRAC) / n:g}" if shared
                            else f"{CPU_TOTAL / n:g}",
      ...
  }
  if shared:
      env["APP_CACHE_MODE"] = "redis"
      env["REDIS_CPUS"] = f"{CPU_TOTAL * REDIS_FRAC:g}"
  ```
  | 줄 | 근거 해설 |
  |----|----------|
  | shared 분기 | 공유 캐시는 총량 그대로 — /2 하면 5,000 짜리 arm 을 재게 됨(침묵 오염) |
- **리뷰 연습 포인트**: 불변식 렌즈 — 네 arm 의 (앱 CPU 합 + redis CPU) 가 전부 CPU_TOTAL 인지 눈으로 검산해보라.

## 2. 기계적 변경 (M)

- `KeyController.kt`·`AdminController.kt` — import·필드 타입 `LruCache`→`KeyCache` (동작 동일: 동일 시그니처 호출만).

## 3. 생성물 (G)

- `results/phase-d-redis/*` — 측정 raw (J-2~5 의 실행 산출물, gitignore 관례).
