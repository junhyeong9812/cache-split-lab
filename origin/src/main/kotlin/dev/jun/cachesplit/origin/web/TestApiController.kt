package dev.jun.cachesplit.origin.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * 초기 스켈레톤 — API 모양만 정의하고 값은 전부 0 을 반환한다.
 */
@RestController
class TestApiController {

    /**
     * 앱 노드가 캐시 미스일 때만 호출한다.
     *
     * 최종 동작: 메모리에 올려둔 CSV 에서 값을 찾아 **고정 지연 후** 반환.
     *
     * 지연은 sleep 으로 흉내낸다 — 실제 IO 가 아니므로 분산이 거의 없고,
     * 그게 목적이다. 미스 비용이 항상 정확히 같아야 arm 간 지연 차이가
     * 순수하게 미스 횟수 차이로만 설명된다.
     */
    @GetMapping("/origin/{id}")
    fun getValue(@PathVariable id: String): OriginResponse =
        OriginResponse(id = id, value = 0, delayMillis = 0)

    /**
     * origin 이 받은 요청 수 = 시스템 전체의 미스 횟수.
     *
     * 앱 노드들의 miss 카운터 합과 이 숫자가 **일치해야 한다.**
     * 어긋나면 둘 중 하나가 틀린 것이다 — 카운터 경합이나 재시도를 잡는 교차 검증.
     */
    @GetMapping("/admin/stats")
    fun stats(): OriginStatsResponse =
        OriginStatsResponse(requests = 0, datasetSize = 0, delayMillis = 0)
}

data class OriginResponse(
    val id: String,
    val value: Long,
    val delayMillis: Long,
)

data class OriginStatsResponse(
    val requests: Long,
    val datasetSize: Int,
    val delayMillis: Long,
)
