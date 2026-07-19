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
    /** arm D 전용 — mode=redis 일 때만 읽힌다. 기본값이 있어 A/B/C 는 영향 없음. */
    val redis: Redis = Redis(),
) {
    /**
     * mode: local(인메모리 — arm A/B/C, 기본) | redis(공유 — arm D).
     * capacity 는 local 이면 노드당, redis 면 **총량**(캐시가 하나) — 계산은 러너 소유.
     */
    data class Cache(val capacity: Int, val mode: String = "local")
    data class Origin(val baseUrl: String)
    data class Redis(val uri: String = "redis://redis:6379")
}
