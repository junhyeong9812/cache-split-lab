package dev.jun.cachesplit.app.web

import dev.jun.cachesplit.app.cache.KeyCache
import dev.jun.cachesplit.app.config.AppProperties
import org.springframework.beans.factory.annotation.Value
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
    private val cache: KeyCache,
    private val props: AppProperties,
    @Value("\${server.tomcat.threads.max}") private val maxThreads: Int,
) {

    /** 히트율 — 주 지표. 워밍업 plateau 탐지에도 이 엔드포인트를 폴링한다. */
    @GetMapping("/stats")
    fun stats(): StatsResponse {
        val s = cache.stats()
        return StatsResponse(
            hits = s.hits, misses = s.misses, hitRate = s.hitRate,
            cacheSize = s.size, capacity = s.capacity,
            threadsActive = Thread.activeCount(),
            inFlight = KeyController.inFlight.get(),
            inFlightMax = KeyController.inFlightMax.get(),
            threadsMax = maxThreads,
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
        KeyController.inFlightMax.set(0)
        return ResetResponse(cleared = 0, nodeId = props.nodeId)
    }

    /** 캐시까지 통째로 비운다. */
    @PostMapping("/reset")
    fun reset(): ResetResponse = ResetResponse(cleared = cache.clear(), nodeId = props.nodeId)
}

data class StatsResponse(
    val hits: Long, val misses: Long, val hitRate: Double,
    val cacheSize: Int, val capacity: Int,
    /** JVM 전체 스레드 — 참고용(GC·JIT·idle 포함이라 병목 판정엔 못 쓴다) */
    val threadsActive: Int,
    /** 지금 처리 중인 요청 수 — 정확한 in-flight */
    val inFlight: Int,
    /** 리셋 이후 관측된 최대 in-flight. threadsMax 에 붙으면 스레드가 묶는 것이다 */
    val inFlightMax: Int,
    /** tomcat 워커 상한 (arm 간 총량 고정) */
    val threadsMax: Int,
    val nodeId: String,
)

data class CacheKeysResponse(val size: Int, val keys: List<String>, val nodeId: String)
data class ResetResponse(val cleared: Int, val nodeId: String)
