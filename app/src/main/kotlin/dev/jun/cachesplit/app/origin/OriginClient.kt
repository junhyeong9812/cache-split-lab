package dev.jun.cachesplit.app.origin

import dev.jun.cachesplit.app.config.AppProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * 캐시 미스일 때만 호출된다. 즉 이 클라이언트의 호출 횟수 = 이 노드의 미스 횟수.
 * origin 의 요청 카운터와 앱들의 miss 합이 일치해야 한다 (교차 검증).
 */
@Component
class OriginClient(props: AppProperties) {

    private val client: RestClient = RestClient.builder()
        .baseUrl(props.origin.baseUrl)
        .build()

    fun fetch(id: String): Long? =
        client.get()
            .uri("/origin/{id}", id)
            .retrieve()
            .body(OriginResponse::class.java)
            ?.value

    data class OriginResponse(val id: String, val value: Long, val delayMillis: Long)
}
