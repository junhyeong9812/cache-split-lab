package dev.jun.cachesplit.app.cache

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 직접 짠 LRU. **엔트리 개수로 정확히** 크기를 강제한다.
 *
 * Caffeine 을 안 쓰는 이유 (docs/DECISIONS.md §9):
 *   1. Caffeine 은 LRU 가 아니라 W-TinyLFU — 하필 우리 축인 Zipf 쏠림에 특화된 정책이라
 *      재는 대상이 바뀐다.
 *   2. `maximumSize(n)` 은 근사치 — 최상위 불변식이 "총 캐시 용량 고정"인데 근사면 곤란하다.
 *   3. LRU 는 이론 히트율을 근사할 수 있어 "이론 vs 실측" 대조가 곧 기준소스가 된다.
 *
 * ── 동시성 ────────────────────────────────────────────────────────────────
 * `LinkedHashMap(accessOrder = true)` 는 **`get()` 이 자료구조를 변경한다**
 * (엔트리를 접근 순서 리스트의 끝으로 옮김). 즉 **동시에 읽기만 해도 깨진다.**
 * 예외가 뜨면 차라리 다행이고, 조용히 링크가 꼬이거나 카운터가 유실되면
 * **그럴듯하고 틀린 히트율**이 나온다 — 이 실험의 지배적 실패모드다.
 *
 * 그래서 모든 접근을 락으로 감싼다. 카운터도 **락 안에서** 증가시킨다
 * (밖에 두면 경합으로 유실되고, 유실된 히트율은 조용히 틀린다).
 *
 * origin 호출은 이 락 **밖**에서 일어난다(호출부 참조) — 락을 쥔 채 5ms 를 기다리면
 * 캐시가 통째로 직렬화되어 측정 대상이 캐시가 아니라 락이 된다.
 */
class LruCache(override val capacity: Int) : KeyCache {

    init {
        require(capacity > 0) { "capacity 는 1 이상이어야 한다: $capacity" }
    }

    private val lock = ReentrantLock()

    /**
     * accessOrder = true → get() 이 접근 순서를 갱신 = LRU.
     * removeEldestEntry 가 size > capacity 일 때 가장 오래된 것을 축출한다.
     *
     * 초기 용량을 capacity/0.75 로 잡아 런 도중 rehash 가 일어나지 않게 한다
     * (rehash 는 측정 중 지연 튐을 만들고, 그건 캐시 동작이 아니라 자료구조 부작용이다).
     */
    private val map = object : LinkedHashMap<String, Long>(
        (capacity / 0.75f).toInt() + 1, 0.75f, true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > capacity
    }

    private var hits = 0L
    private var misses = 0L

    /** 히트면 값, 미스면 null. 카운터는 여기서만 움직인다. */
    override fun get(key: String): Long? = lock.withLock {
        val value = map[key]
        if (value != null) hits++ else misses++
        value
    }

    override fun put(key: String, value: Long): Unit = lock.withLock {
        map[key] = value
    }

    override fun stats(): CacheStats = lock.withLock {
        CacheStats(hits = hits, misses = misses, size = map.size, capacity = capacity)
    }

    /**
     * 지금 캐시에 든 키 전부 — **이 실험의 스모킹 건.**
     *
     * 히트율 차이는 "결과"고, 두 노드 캐시의 **교집합**이 "원인"이다.
     * arm B 는 교집합이 크고(중복), arm C 는 0 이어야(분할) 한다.
     * 이게 없으면 히트율 숫자만 보고 "그런가보다" 하는 것이다.
     */
    override fun keys(): List<String> = lock.withLock { map.keys.toList() }

    /** 워밍업 카운터를 버리고 측정 구간을 새로 시작할 때. */
    override fun resetCounters(): Unit = lock.withLock {
        hits = 0
        misses = 0
    }

    /** 캐시까지 통째로 비운다. 반환값은 비우기 전 엔트리 수. */
    override fun clear(): Int = lock.withLock {
        val n = map.size
        map.clear()
        hits = 0
        misses = 0
        n
    }
}

data class CacheStats(
    val hits: Long,
    val misses: Long,
    val size: Int,
    val capacity: Int,
) {
    val hitRate: Double get() = if (hits + misses == 0L) 0.0 else hits.toDouble() / (hits + misses)
}
