package dev.jun.cachesplit.app.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 초기 스켈레톤 — API 모양만 정의하고 값은 전부 0 을 반환한다.
 *
 * 여기 정의된 네 엔드포인트가 이 실험의 전체 표면이다.
 * 구현이 붙는 순서와 그때 뭘 조심해야 하는지는 각 메서드의 주석에 적었다.
 */
@RestController
class TestApiController {

    /**
     * 실험 트래픽 — k6 가 때리는 유일한 경로. nginx 가 이 경로만 프록시한다.
     *
     * 최종 동작: 캐시 조회 → 히트면 반환, 미스면 origin 호출 후 캐시에 넣고 반환.
     *
     * 경로가 `/key/{id}` 인 것은 arm C 의 `hash $uri` 와 짝이다 —
     * URI 가 키와 1:1 이어야 "같은 키는 항상 같은 노드" 가 성립한다.
     */
    @GetMapping("/key/{id}")
    fun getKey(@PathVariable id: String): KeyResponse =
        KeyResponse(id = id, value = 0, cached = false, nodeId = "0")

    /**
     * 히트율 — 이 프로젝트의 주 지표.
     *
     * 카운터라서 CPU 경합에 안 흔들린다. 그래서 부하 생성기가 SUT 와 같은 머신에
     * 있어도 이 숫자만은 신뢰할 수 있다 (우리는 어차피 분리하지만).
     *
     * 주의: 카운터 증가가 캐시 락 밖에 있으면 경합으로 유실된다.
     *       유실된 히트율은 "그럴듯하고 틀린 숫자"로 나온다 — 이 실험의 지배적 실패모드다.
     */
    @GetMapping("/admin/stats")
    fun stats(): StatsResponse =
        StatsResponse(hits = 0, misses = 0, hitRate = 0.0, cacheSize = 0, capacity = 0, nodeId = "0")

    /**
     * 캐시에 지금 어떤 키가 들어있나 — **이 실험의 스모킹 건.**
     *
     * 히트율 차이는 "결과"고, 두 노드 캐시의 **교집합**이 "원인"이다.
     *   arm B → 두 노드의 교집합 ≈ 100% (중복이 실재한다는 직접 증거)
     *   arm C → 두 노드의 교집합 ≈ 0%   (분할이 실재한다는 직접 증거)
     *
     * 이게 없으면 히트율 숫자만 보고 "그런가보다" 하는 것이고,
     * 우리가 잰 게 정말 그 메커니즘인지 증명할 수 없다.
     */
    @GetMapping("/admin/cache/keys")
    fun cacheKeys(): CacheKeysResponse =
        CacheKeysResponse(size = 0, keys = emptyList(), nodeId = "0")

    /**
     * 캐시·카운터 초기화 — 런 사이에 호출한다.
     *
     * arm 을 바꿔 띄울 때마다 컨테이너가 새로 뜨므로 꼭 필요하진 않지만,
     * 같은 컨테이너에서 워밍업과 측정을 나눌 때 워밍업 카운터를 버리는 데 쓴다.
     */
    @PostMapping("/admin/reset")
    fun reset(): ResetResponse =
        ResetResponse(cleared = 0, nodeId = "0")
}

/**
 * @param cached 이 응답이 캐시에서 나왔나(히트) origin 에서 나왔나(미스).
 *               k6 쪽에서도 히트율을 교차 검증할 수 있게 응답에 실어 보낸다 —
 *               앱 카운터와 k6 집계가 어긋나면 둘 중 하나가 틀린 것이다.
 * @param nodeId 어느 노드가 응답했나. nginx 의 `$upstream_addr` 로그와 대조해
 *               라우팅이 의도대로 도는지 확인하는 용도.
 */
data class KeyResponse(
    val id: String,
    val value: Long,
    val cached: Boolean,
    val nodeId: String,
)

data class StatsResponse(
    val hits: Long,
    val misses: Long,
    val hitRate: Double,
    val cacheSize: Int,
    val capacity: Int,
    val nodeId: String,
)

data class CacheKeysResponse(
    val size: Int,
    val keys: List<String>,
    val nodeId: String,
)

data class ResetResponse(
    val cleared: Int,
    val nodeId: String,
)
