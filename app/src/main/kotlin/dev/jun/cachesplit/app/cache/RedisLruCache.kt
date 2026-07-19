package dev.jun.cachesplit.app.cache

import io.lettuce.core.RedisClient
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import java.util.concurrent.atomic.AtomicLong

/**
 * arm D — 공유 Redis 위의 **엔트리 수 정확 LRU.**
 *
 * Redis 기본 축출(maxmemory-policy allkeys-lru)을 쓰지 않는 이유는 LruCache 가
 * Caffeine 을 버린 이유와 같다 (DECISIONS.md §10):
 *   1. maxmemory 는 **바이트** 기준 — 총량 불변식은 **엔트리 수**다.
 *   2. Redis LRU 는 **샘플링 근사**(maxmemory-samples 5 개 중 최소) — 정확 LRU 가
 *      아니라서 이론(Che)과의 대조가 무의미해진다.
 * 그래서 HASH(값) + ZSET(접근 순서) + 카운터를 Lua 로 묶어 직접 구현한다.
 *
 * ── 원자성 ────────────────────────────────────────────────────────────────
 * Redis 는 스크립트 하나를 단일 스레드로 실행한다 — Lua 안의 HGET+ZADD(승격),
 * HSET+축출 루프는 그 자체로 원자적이다. LruCache 의 ReentrantLock 과 동일한
 * 역할을 Redis 의 단일 스레드가 한다. **그 단일 스레드가 곧 이 arm 의 측정
 * 대상이다** — 모든 노드의 모든 요청이 여기서 직렬화된다.
 *
 * ── 접근 순서 = 단조 카운터 ──────────────────────────────────────────────
 * ZSET 점수에 시계를 쓰면 같은 ms 의 접근이 동점이 되어 축출 순서가 사전순으로
 * 오염된다. INCR 카운터는 전 노드에 걸쳐 전순서를 준다 — LinkedHashMap 의
 * 접근 순서 리스트와 등가.
 *
 * ── 카운터(hits/misses)는 앱 노드별 ──────────────────────────────────────
 * 기존 러너가 노드별 /admin/stats 를 합산하므로(collect) 앱 쪽 AtomicLong 으로
 * 노드별 집계를 유지한다. 캐시 상태(size·keys)는 공유 Redis 의 것을 그대로 읽는다.
 */
class RedisLruCache(
    override val capacity: Int,
    redisUri: String,
) : KeyCache, AutoCloseable {

    init {
        require(capacity > 0) { "capacity 는 1 이상이어야 한다: $capacity" }
    }

    private val client: RedisClient = RedisClient.create(redisUri)

    /**
     * 커넥션 하나를 전 스레드가 공유한다 — Lettuce 의 StatefulRedisConnection 은
     * 스레드 세이프하고 요청을 파이프라인으로 직렬화한다. 커넥션 풀을 일부러 안 쓴다:
     * "나이브한 외부화"가 이 arm 의 정의고, 풀 튜닝은 별도 변수다.
     */
    private val conn: StatefulRedisConnection<String, String> = client.connect()

    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)

    companion object {
        const val KEY_HASH = "cache:h"     // key → value
        const val KEY_LRU = "cache:z"      // key → 접근 카운터 (낮을수록 오래됨)
        const val KEY_SEQ = "cache:seq"    // 단조 접근 카운터

        // 히트면 접근 순서를 승격하고 값을 반환, 미스면 false 반환.
        val GET_LUA = """
            local v = redis.call('HGET', KEYS[1], ARGV[1])
            if v then
              redis.call('ZADD', KEYS[2], redis.call('INCR', KEYS[3]), ARGV[1])
            end
            return v
        """.trimIndent()

        // 비우기 — 크기 조회와 삭제를 한 스크립트로(원자). 따로 보내면 그 사이
        // 다른 노드의 put 이 끼어들어 반환값과 실제 삭제 대상이 어긋난다.
        val CLEAR_LUA = """
            local n = redis.call('HLEN', KEYS[1])
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
            return n
        """.trimIndent()

        // 넣고, 접근 순서를 갱신하고, 용량 초과분을 LRU 순으로 축출한다.
        val PUT_LUA = """
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
        """.trimIndent()
    }

    private val getSha: String = conn.sync().scriptLoad(GET_LUA)
    private val putSha: String = conn.sync().scriptLoad(PUT_LUA)
    private val clearSha: String = conn.sync().scriptLoad(CLEAR_LUA)

    private val scriptKeys = arrayOf(KEY_HASH, KEY_LRU, KEY_SEQ)

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

    override fun put(key: String, value: Long) {
        conn.sync().evalsha<Long>(
            putSha, ScriptOutputType.INTEGER,
            scriptKeys, key, value.toString(), capacity.toString(),
        )
    }

    override fun stats(): CacheStats {
        val size = conn.sync().hlen(KEY_HASH).toInt()
        return CacheStats(hits = hits.get(), misses = misses.get(), size = size, capacity = capacity)
    }

    override fun keys(): List<String> = conn.sync().hkeys(KEY_HASH)

    /**
     * ⚠️ 러너 규약 전제: 리셋은 **무트래픽 구간에서만** 부른다 (워밍업 k6 종료 확인 후).
     * LruCache 와 달리 여기선 get 의 [Lua 실행 → 카운터 증가]가 락으로 묶여 있지 않아,
     * in-flight 요청과 겹치면 경계의 몇 건이 새 구간으로 샌다. 측정 절차가 정지를
     * 보장하므로 수용 — 절차 없이 호출하면 조용히 틀린 히트율이 된다 (review-log D1).
     */
    override fun resetCounters() {
        hits.set(0)
        misses.set(0)
    }

    override fun clear(): Int {
        val n: Long = conn.sync().evalsha(clearSha, ScriptOutputType.INTEGER, scriptKeys)
        hits.set(0)
        misses.set(0)
        return n.toInt()
    }

    /** Spring 이 빈 소멸 시 호출(AutoCloseable 추론) — 커넥션·이벤트 루프 정리. */
    override fun close() {
        conn.close()
        client.shutdown()
    }
}
