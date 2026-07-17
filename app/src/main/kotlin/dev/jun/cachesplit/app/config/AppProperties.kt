package dev.jun.cachesplit.app.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * arm 별 변수는 여기로만 들어온다. 코드는 arm 을 모른다.
 *
 * cache.capacity 가 arm 을 가르는 유일한 앱 파라미터다:
 *   arm A → CACHE_TOTAL       (10,000)
 *   arm B → CACHE_TOTAL / 2   (5,000) × 2 노드
 *   arm C → CACHE_TOTAL / 2   (5,000) × 2 노드
 * 계산은 scripts/run-arm.sh 가 한다 — 총량이 단일 출처여야 반쪽이 보장된다.
 */
@ConfigurationProperties(prefix = "app")
data class AppProperties(
    /** 어느 노드가 응답했나. nginx $upstream_addr 로그와 대조용. */
    val nodeId: String,
    val cache: Cache,
    val origin: Origin,
) {
    data class Cache(val capacity: Int)
    data class Origin(val baseUrl: String)
}
