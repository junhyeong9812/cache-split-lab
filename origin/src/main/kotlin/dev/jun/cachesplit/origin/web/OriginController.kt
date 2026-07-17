package dev.jun.cachesplit.origin.web

import dev.jun.cachesplit.origin.store.CsvStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class OriginController(private val store: CsvStore) {

    /** 앱 노드가 캐시 미스일 때만 호출한다. */
    @GetMapping("/origin/{id}")
    fun getValue(@PathVariable id: String): OriginResponse {
        val value = store.get(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "없는 키: $id")
        return OriginResponse(id = id, value = value, delayMillis = store.delayMillis())
    }

    /**
     * origin 이 받은 요청 수 = 시스템 전체의 미스 횟수.
     * 앱 노드들의 miss 카운터 합과 **일치해야 한다** — 어긋나면 카운터 경합이나
     * 몰래 일어난 재시도를 잡는 교차 검증이다.
     */
    @GetMapping("/admin/stats")
    fun stats(): OriginStatsResponse = OriginStatsResponse(
        requests = store.requestCount.get(),
        datasetSize = store.size(),
        delayMillis = store.delayMillis(),
    )
}

data class OriginResponse(val id: String, val value: Long, val delayMillis: Long)
data class OriginStatsResponse(val requests: Long, val datasetSize: Int, val delayMillis: Long)
