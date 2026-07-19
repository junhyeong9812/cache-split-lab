# 학습 기록 (Learned)

> 작성일: 2026-07-19
> 관련 산출물: `docs/plans/2026-07-19/arm-d-redis/task.md`
> 작업 요약: 공유 Redis 캐시(arm D) 구현 — Lua 엔트리 정확 LRU — 및 포화·지연 실측

---

## 1. 사용된 라이브러리

| 라이브러리 | 버전 | 용도 | 왜 선택했는가 |
|-----------|------|------|-------------|
| io.lettuce:lettuce-core | Boot 3.5.0 BOM 관리 | 앱→Redis 클라이언트 | 커넥션 1개 공유가 스레드 세이프(파이프라인) — "나이브 외부화"에 정확히 맞는 모델. Jedis 는 커넥션당 단일 스레드라 풀 필수 |
| redis:7-alpine (docker) | 7.x | 공유 캐시 저장소 | 표준. persistence 전부 off 로 측정 노이즈 제거 |
| Redis Lua (EVAL/EVALSHA) | 내장 | LRU 원자 연산 | 스크립트 = 임계 구역 (TECHNICAL §개념 2) |

## 2. 핵심 함수 / 메서드

### Lettuce

| 함수/메서드 | 시그니처 | 역할 | 사용 위치 |
|------------|---------|------|----------|
| `RedisClient.create(uri)` | `(String) -> RedisClient` | 클라이언트 생성 | `RedisLruCache.kt:46` |
| `client.connect()` | `-> StatefulRedisConnection<String,String>` | 공유 커넥션(스레드 세이프) | `RedisLruCache.kt:53` |
| `conn.sync().scriptLoad(lua)` | `(String) -> String(SHA)` | 스크립트 사전 로드 | `RedisLruCache.kt:87-88` |
| `conn.sync().evalsha(sha, type, keys, args…)` | `-> T` | 원자 실행 | get/put |
| `conn.sync().hlen/hkeys/del` | — | size·keys·clear | stats/keys/clear |

**사용 예시:**
```kotlin
    override fun get(key: String): Long? {
        val v: String? = conn.sync().evalsha(getSha, ScriptOutputType.VALUE, scriptKeys, key)
        return if (v != null) {
            hits.incrementAndGet()
            v.toLong()
        } else {
            misses.incrementAndGet()
            null
        }
    }
```
- 출처: `app/src/main/kotlin/dev/jun/cachesplit/app/cache/RedisLruCache.kt:92-101`

**코드 설명:**
> `evalsha(sha, VALUE, keys, key)` — 로드해 둔 GET Lua 를 원자 실행. 반환 `VALUE` = 문자열 또는 null.
> `hits/misses` — 노드별 AtomicLong. 러너의 노드별 수집(collect) 계약을 유지하기 위해 앱 쪽에 둔다.

## 3. 어노테이션 / 데코레이터

| 어노테이션/데코레이터 | 소속 | 역할 | 적용 대상 |
|--------------------|------|------|----------|
| `@ConfigurationProperties(prefix="app")` | Spring Boot | env→설정 바인딩 (Kotlin 생성자 기본값 = 선택 설정) | AppProperties (`cache.mode`·`redis.uri` 추가) |

**동작 원리:** Boot 3 의 생성자 바인딩은 데이터클래스 기본값을 "미설정 시 값"으로 쓴다 —
`Redis(val uri: String = "…")` 덕에 arm A/B/C 는 새 설정 없이 그대로 돈다.

## 4. 수정 전/후 코드 비교

### 파일명: `app/src/main/kotlin/dev/jun/cachesplit/app/config/CacheConfig.kt`

**수정 전:**
```kotlin
    /** 노드당 캐시 하나. capacity 는 주입된 값 그대로 — 조용한 기본값 없음. */
    @Bean
    fun lruCache(props: AppProperties): LruCache = LruCache(props.cache.capacity)
```

**수정 후:**
```kotlin
    @Bean
    fun keyCache(props: AppProperties): KeyCache = when (props.cache.mode) {
        "local" -> LruCache(props.cache.capacity)
        "redis" -> RedisLruCache(props.cache.capacity, props.redis.uri)
        else -> error("알 수 없는 CACHE_MODE: ${props.cache.mode} (local | redis)")
    }
```

**변경 이유:** 캐시 배치를 코드가 아니라 설정이 가르게 — "arm 간 차이는 배치뿐" 유지.
오타는 기동 실패(조용한 폴백 금지).

**변경된 함수/메서드 설명:**
| 함수/메서드 | 변경 내용 | 이유 |
|------------|----------|------|
| keyCache | 반환 타입 LruCache→KeyCache, mode 분기 | 확장점 + 침묵 오염 차단 |
| (컨트롤러 2개) | 필드 타입 KeyCache 로 | 구체 타입 결합 제거 (M 항목) |

## 5. 동작 구조

### 실행 흐름

```
k6 → nginx(RR) → 앱 노드 (tomcat 스레드 100)
  → RedisLruCache.get → [redis 단일 스레드: GET Lua = HGET + ZADD(INCR)]
    ├─ 히트 → 응답 (cached: true)
    └─ 미스 → OriginClient.fetch (5ms) → RedisLruCache.put
              → [redis: PUT Lua = HSET + ZADD + (HLEN>cap? ZPOPMIN→HDEL)*]
              → 응답 (cached: false)
```

### 컴포넌트별 역할

| 컴포넌트 | 파일 | 역할 | 호출하는 메서드 |
|----------|------|------|---------------|
| KeyCache | cache/KeyCache.kt | 캐시 계약 | — |
| RedisLruCache | cache/RedisLruCache.kt | 공유 LRU (Lua) | evalsha·hlen·hkeys·del |
| CacheConfig | config/CacheConfig.kt | mode → 구현 선택 | — |
| redis 서비스 | arms/compose.yml | 상태 소유 | — |
| run_arm.py | scripts/run_arm.py | 총량→배분 계산 (shared 분기) | — |

### 데이터 흐름

```
key-<i> (URI)
  → GET Lua: HGET cache:h key → (히트) ZADD cache:z (INCR cache:seq) key → "value"
  → (미스) origin CSV 값 → PUT Lua: HSET + ZADD + 축출 → 응답 JSON {cached, nodeId}
```

## 6. 디자인 패턴

| 패턴 | 적용 위치 | 왜 사용했는가 | 구조 |
|------|----------|-------------|------|
| Strategy (설정 선택) | KeyCache ← LruCache / RedisLruCache | 배치를 설정으로 | 인터페이스 + when |
| 임계 구역 이전 | Lua 스크립트 | 락을 저장소 단일 스레드로 | GET/PUT 각 1 스크립트 |
| 측정 전 게이트 | 용량 3 시나리오 | 도구 검증 후 측정 | 별도 일회용 redis |

**패턴 상세:**

### 임계 구역 이전 (lock → single-threaded store)
- **의도**: 다중 노드 동시 접근에서 LRU 순서의 원자성 확보 — 앱 락으론 노드 간 불가능.
- **구조**: 읽기 경로(HGET+승격)와 쓰기 경로(HSET+축출)를 각각 하나의 Lua 로.
- **이 프로젝트에서의 적용**:
```lua
local v = redis.call('HGET', KEYS[1], ARGV[1])
if v then
  redis.call('ZADD', KEYS[2], redis.call('INCR', KEYS[3]), ARGV[1])
end
return v
```
- 출처: `RedisLruCache.kt:60-66` (GET_LUA)

## 7. 설정 / 컨벤션

| 항목 | 값 | 이유 |
|------|---|------|
| CACHE_MODE | local(기본) / redis | 오타 = 기동 실패 |
| REDIS_FRAC | 0.2 (run_arm.py) | redis 몫 — 총량 고정 안 (실측: 무릎에서 쿼터 ~70% 사용) |
| redis persistence | save ""·appendonly no | fork·fsync 는 측정 노이즈 |
| 커넥션 | 노드당 1개 공유(풀 없음) | 나이브 외부화의 정의 — 직렬화 지점 노출 |

## 8. 테스트에서 사용된 것들

### 테스트 프레임워크

| 라이브러리 | 버전 | 용도 |
|-----------|------|------|
| 기존 JUnit5 (LruCacheTest) | Boot BOM | 인메모리 구현 회귀 (빌드에 포함) |
| redis-cli 시나리오 (일회용 컨테이너) | redis 7 | Lua LRU 정확성 — JVM 밖에서 스크립트 자체를 검정 |

### 테스트 유틸리티 / 헬퍼 · Mock/Stub · 어노테이션 · 픽스처

해당 없음 — Lua 검정은 실 redis 에 실 스크립트로(모킹하면 검정 대상이 사라짐).

### Assertion 메서드

| 메서드 | 소속 | 검증 내용 | 예시 |
|--------|------|----------|------|
| 출력 비교 (shell) | lru-test.sh | 축출 순서·size | "k4 후: k1,k3,k4" |

**대표 테스트 코드:**
```sh
put k1 v1; put k2 v2; put k3 v3
get k1 >/dev/null          # k1 승격 → 최고령은 k2
put k4 v4                  # k2 축출 기대
echo "k4 후: $($R HKEYS c:h | sort | paste -sd,)"
get k2 >/dev/null          # 미스 (순서 무변)
put k5 v5                  # k3 축출 기대
echo "k5 후: $($R HKEYS c:h | sort | paste -sd,)"
```
- 출처: scratchpad `lru-test.sh` (일회용 — 실행 기록은 task.md §3, 결과: k1,k3,k4 / k1,k4,k5 정확)

## 9. 새로 알게 된 것

- 외부화 비용의 지배 항은 왕복 지연이 아니라 **캐시 계층의 CPU 예산 몫**이었다 —
  같은 호스트 브리지 왕복은 +0.08ms 뿐인데 무릎은 −40%.
- Lettuce 공유 커넥션 하나로 tomcat 100 스레드의 동기 호출이 파이프라인으로 잘 돈다 —
  풀 없이도 저~중부하는 문제없고, 그 직렬화가 바로 아키텍처의 본질이다.
- Redis Lua 안의 `redis.call('INCR', …)` 반환값을 그대로 ZADD 점수로 쓸 수 있다 —
  왕복 없는 단조 카운터.
- compose `depends_on.<svc>.required: false` 로 profile 꺼진 서비스 의존을 무시할 수
  있다 — 단일 compose 불변식을 profile 확장과 양립시키는 열쇠.
- 계단 첫 스텝 과도 상태가 두 페이즈 연속 재발 — 프로토콜에 "버리는 선행 스텝"이 필요.

## 10. 더 공부할 것

| 주제 | 왜 공부해야 하는가 | 참고 자료 |
|------|-----------------|----------|
| D 의 과부하 goodput 역행 | ~5s 절단의 계층(k6 gracefulStop vs 서버 큐) 미분리 — 측정 도구 이해의 구멍 | k6 scenarios 문서·nginx proxy 타임아웃 |
| Lettuce 파이프라인 내부(단일 커넥션 순서 보장) | 100 스레드 동기 호출의 공정성·HOL 블로킹 여부 | lettuce wiki Pipelining |
| REDIS_FRAC 최적 배분 | 외부화 용량 함정의 완화 곡선 — 0.1/0.3 실측 | phase-d gate 미해결 ② |
