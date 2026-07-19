package dev.jun.cachesplit.app.cache

/**
 * 캐시 계층의 계약 — arm A/B/C 는 인메모리(LruCache), arm D 는 공유 Redis(RedisLruCache).
 *
 * 컨트롤러는 이 인터페이스만 본다. arm 을 가르는 것은 코드가 아니라 설정이어야
 * "arm 간 차이는 배치뿐" 불변식이 유지된다 (CACHE_MODE — application.yml).
 *
 * 계약(두 구현이 동일해야 하는 것):
 *   - LRU 시맨틱: get 히트가 접근 순서를 갱신하고, 초과 시 최소 최근 사용을 축출
 *   - capacity 는 **엔트리 개수로 정확히** (바이트·근사 금지 — DECISIONS.md §10)
 *   - hits/misses 는 get 에서만 움직인다 (put 은 카운터 무관)
 */
interface KeyCache {
    val capacity: Int

    /** 히트면 값, 미스면 null. 카운터는 여기서만 움직인다. */
    fun get(key: String): Long?

    fun put(key: String, value: Long)

    fun stats(): CacheStats

    /** 지금 캐시에 든 키 전부 — 교집합 분석용 스모킹 건. */
    fun keys(): List<String>

    /** 워밍업 카운터를 버리고 측정 구간을 새로 시작할 때. 캐시 내용은 유지. */
    fun resetCounters()

    /** 캐시까지 통째로 비운다. 반환값은 비우기 전 엔트리 수. */
    fun clear(): Int
}
