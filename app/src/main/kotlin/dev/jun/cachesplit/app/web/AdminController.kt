package dev.jun.cachesplit.app.web

import dev.jun.cachesplit.app.cache.LruCache
import dev.jun.cachesplit.app.config.AppProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * nginx 는 /key/ 만 프록시한다. 이 경로들은 노드 포트(8081/8082)로 직접 긁는다 —
 * nginx 를 통하면 어느 노드가 답할지 통제할 수 없어서 노드별 상태를 못 본다.
 */
@RestController
@RequestMapping("/admin")
class AdminController(
    private val cache: LruCache,
    private val props: AppProperties,
) {

    /** 히트율 — 주 지표. 워밍업 plateau 탐지에도 이 엔드포인트를 폴링한다. */
    @GetMapping("/stats")
    fun stats(): StatsResponse {
        val s = cache.stats()
        return StatsResponse(
            hits = s.hits, misses = s.misses, hitRate = s.hitRate,
            cacheSize = s.size, capacity = s.capacity,
            threadsActive = Thread.activeCount(),
            nodeId = props.nodeId,
        )
    }

    /**
     * 캐시에 지금 든 키 전부 — **스모킹 건.**
     * 두 노드 것을 받아 교집합을 내면 B 의 중복 / C 의 분할이 직접 증명된다.
     */
    @GetMapping("/cache/keys")
    fun cacheKeys(): CacheKeysResponse {
        val keys = cache.keys()
        return CacheKeysResponse(size = keys.size, keys = keys, nodeId = props.nodeId)
    }

    /** 워밍업 종료 후 호출 — 캐시 내용은 남기고 카운터만 0 으로. */
    @PostMapping("/reset-counters")
    fun resetCounters(): ResetResponse {
        cache.resetCounters()
        return ResetResponse(cleared = 0, nodeId = props.nodeId)
    }

    /** 캐시까지 통째로 비운다. */
    @PostMapping("/reset")
    fun reset(): ResetResponse = ResetResponse(cleared = cache.clear(), nodeId = props.nodeId)
}

data class StatsResponse(
    val hits: Long, val misses: Long, val hitRate: Double,
    val cacheSize: Int, val capacity: Int,
    /** 스레드가 묶이는지 CPU 가 묶는지 판정용 (Phase 0 — DECISIONS.md §17) */
    val threadsActive: Int,
    val nodeId: String,
)

data class CacheKeysResponse(val size: Int, val keys: List<String>, val nodeId: String)
data class ResetResponse(val cleared: Int, val nodeId: String)
