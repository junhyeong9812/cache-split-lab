package dev.jun.cachesplit.app.cache

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 이 실험의 모든 숫자가 이 클래스에 달려 있다.
 * LRU 순서가 틀리거나 카운터가 유실되면 **그럴듯하고 틀린 히트율**이 나오고,
 * 그건 겉으로 구분이 안 된다.
 */
class LruCacheTest {

    /** docs/GLOSSARY.md §2 의 예시를 그대로 검증한다. */
    @Test
    fun `LRU 순서 — 가장 오래 안 쓴 것을 축출한다`() {
        val cache = LruCache(capacity = 3)

        listOf("A", "B", "C").forEach { cache.put(it, it.first().code.toLong()) }
        assertEquals(setOf("A", "B", "C"), cache.keys().toSet())

        // A 를 다시 쓴다 → A 가 최신이 되고 B 가 가장 오래된 것이 된다
        assertEquals(65L, cache.get("A"))

        // D 를 넣으면 꽉 찼으므로 축출 — 가장 오래 안 쓴 B 가 나가야 한다
        cache.put("D", 68L)

        assertEquals(setOf("A", "C", "D"), cache.keys().toSet())
        assertNull(cache.get("B"), "B 가 축출됐어야 한다")
    }

    /** get() 이 접근 순서를 갱신하지 않으면 (accessOrder=false) 이 테스트가 깨진다. */
    @Test
    fun `get 이 접근 순서를 갱신한다 — 안 하면 FIFO 가 된다`() {
        val cache = LruCache(capacity = 2)
        cache.put("old", 1)
        cache.put("new", 2)

        cache.get("old")          // old 를 되살린다
        cache.put("third", 3)     // 축출 발생

        // FIFO 였다면 old 가 나갔을 것이다. LRU 니까 new 가 나가야 한다.
        assertEquals(setOf("old", "third"), cache.keys().toSet())
    }

    /**
     * **최상위 불변식**: 캐시는 정확히 capacity 개를 넘지 않는다.
     * A 는 N, B/C 는 N/2 — 이게 근사면 arm 비교가 무효다.
     */
    @Test
    fun `상주 엔트리 수가 capacity 를 절대 넘지 않는다`() {
        val capacity = 100
        val cache = LruCache(capacity)

        repeat(10_000) { i ->
            cache.put("key-$i", i.toLong())
            assertTrue(
                cache.stats().size <= capacity,
                "capacity 초과: ${cache.stats().size} > $capacity (i=$i)",
            )
        }
        assertEquals(capacity, cache.stats().size, "가득 찬 뒤엔 정확히 capacity 여야 한다")
    }

    @Test
    fun `히트 미스 카운터가 정확하다`() {
        val cache = LruCache(capacity = 2)

        cache.get("a")            // miss
        cache.put("a", 1)
        cache.get("a")            // hit
        cache.get("a")            // hit
        cache.get("b")            // miss

        val stats = cache.stats()
        assertEquals(2, stats.hits)
        assertEquals(2, stats.misses)
        assertEquals(0.5, stats.hitRate)
    }

    /**
     * ★ 이 테스트가 이 파일의 존재 이유다.
     *
     * `LinkedHashMap(accessOrder=true)` 는 get() 이 자료구조를 변경하므로
     * 락 없이 동시 접근하면 깨진다. 그리고 카운터가 락 밖에 있으면 증가가 유실된다.
     *
     * 유실된 히트율은 **그럴듯하고 틀린 숫자**로 나온다 — 71% 가 맞아도 71%,
     * 틀려도 71% 처럼 보인다. 그래서 여기서 잡아야 한다.
     *
     * 검증: hits + misses == 전체 요청 수 (한 건도 유실되지 않는다)
     */
    @Test
    fun `동시 접근에서 카운터가 유실되지 않는다`() {
        val cache = LruCache(capacity = 50)
        val threads = 16
        val perThread = 5_000
        val total = threads * perThread

        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            pool.submit {
                start.await()
                repeat(perThread) { i ->
                    val key = "key-${(t * perThread + i) % 200}"   // capacity 넘게 → 축출 유발
                    if (cache.get(key) == null) cache.put(key, i.toLong())
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS), "타임아웃 — 데드락 의심")
        pool.shutdown()

        val stats = cache.stats()
        assertEquals(
            total.toLong(), stats.hits + stats.misses,
            "카운터 유실: ${stats.hits + stats.misses} != $total — 락 밖에서 증가시켰거나 경합",
        )
        assertTrue(stats.size <= 50, "동시 접근 중 capacity 초과: ${stats.size}")
    }

    @Test
    fun `resetCounters 는 캐시를 비우지 않는다 — 워밍업 후 측정 시작용`() {
        val cache = LruCache(capacity = 10)
        cache.put("a", 1)
        cache.get("a")
        cache.get("zzz")

        cache.resetCounters()

        val stats = cache.stats()
        assertEquals(0, stats.hits)
        assertEquals(0, stats.misses)
        assertEquals(1, stats.size, "캐시 내용은 남아 있어야 한다 (워밍업 결과 보존)")
    }
}
