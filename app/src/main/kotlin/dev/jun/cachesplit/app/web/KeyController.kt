package dev.jun.cachesplit.app.web

import dev.jun.cachesplit.app.cache.LruCache
import dev.jun.cachesplit.app.config.AppProperties
import dev.jun.cachesplit.app.origin.OriginClient
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 실험 트래픽 — k6 가 때리는 유일한 경로. nginx 가 이 경로만 프록시한다.
 *
 * 경로가 /key/{id} 인 것은 arm C 의 `hash $uri` 와 짝이다 —
 * URI 가 키와 1:1 이어야 "같은 키는 항상 같은 노드"가 성립한다.
 */
@RestController
class KeyController(
    private val cache: LruCache,
    private val origin: OriginClient,
    private val props: AppProperties,
) {

    companion object {
        /**
         * 지금 처리 중인 요청 수 — **정확한 동시성 측정치.**
         * Thread.activeCount() 는 JVM 전체 스레드(GC·JIT·idle 풀 포함)라
         * "스레드가 묶였나"를 판정할 수 없다. 이건 실제 in-flight 다.
         * Phase 0 에서 CPU 가 묶는지 스레드가 묶는지 가르는 데 쓴다 (DECISIONS.md §17).
         */
        val inFlight = AtomicInteger(0)
        val inFlightMax = AtomicInteger(0)
    }

    @GetMapping("/key/{id}")
    fun getKey(@PathVariable id: String): KeyResponse {
        val n = inFlight.incrementAndGet()
        inFlightMax.updateAndGet { if (n > it) n else it }
        try {
            return handle(id)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun handle(id: String): KeyResponse {
        cache.get(id)?.let {
            return KeyResponse(id = id, value = it, cached = true, nodeId = props.nodeId)
        }

        // ── 락 밖에서 origin 을 호출한다 ──────────────────────────────────
        // 락을 쥔 채 미스 페널티(수 ms)를 기다리면 캐시 전체가 직렬화되어
        // 측정 대상이 캐시가 아니라 락이 된다.
        //
        // 부작용: 같은 키에 대한 동시 미스가 origin 을 중복 호출한다(캐시 스탬피드).
        // single-flight 로 dedup 하지 않는 것은 **의도적**이다 — 나이브한 앱의 실제
        // 동작이고, 두 요청이 각각 미스한 것은 사실이므로 카운터도 정확하다.
        // (docs/DECISIONS.md 빚 D — 고 skew 에서 영향 관찰 예정)
        val value = origin.fetch(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "origin 에 없는 키: $id")

        cache.put(id, value)
        return KeyResponse(id = id, value = value, cached = false, nodeId = props.nodeId)
    }
}

/**
 * @param cached 캐시에서 나왔나(히트) origin 에서 나왔나(미스).
 *               k6 가 이걸로 히트율을 독립 집계한다 — 앱 카운터와 어긋나면 둘 중 하나가 틀린 것.
 */
data class KeyResponse(
    val id: String,
    val value: Long,
    val cached: Boolean,
    val nodeId: String,
)
