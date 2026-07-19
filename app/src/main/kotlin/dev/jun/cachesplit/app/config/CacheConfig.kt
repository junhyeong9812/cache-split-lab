package dev.jun.cachesplit.app.config

import dev.jun.cachesplit.app.cache.KeyCache
import dev.jun.cachesplit.app.cache.LruCache
import dev.jun.cachesplit.app.cache.RedisLruCache
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CacheConfig {
    /**
     * 노드당 캐시 하나. capacity 는 주입된 값 그대로 — 조용한 기본값 없음.
     *
     * mode 가 캐시 배치를 가른다:
     *   local (기본) → 인메모리 LruCache — arm A/B/C
     *   redis        → 공유 RedisLruCache — arm D (capacity 는 **총량** — 캐시가 하나다)
     * 오타는 즉시 기동 실패 — 조용히 local 로 빠지면 arm D 라고 믿고 딴 것을 재게 된다.
     */
    @Bean
    fun keyCache(props: AppProperties): KeyCache = when (props.cache.mode) {
        "local" -> LruCache(props.cache.capacity)
        "redis" -> RedisLruCache(props.cache.capacity, props.redis.uri)
        else -> error("알 수 없는 CACHE_MODE: ${props.cache.mode} (local | redis)")
    }
}
