package dev.jun.cachesplit.app.config

import dev.jun.cachesplit.app.cache.LruCache
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CacheConfig {
    /** 노드당 캐시 하나. capacity 는 주입된 값 그대로 — 조용한 기본값 없음. */
    @Bean
    fun lruCache(props: AppProperties): LruCache = LruCache(props.cache.capacity)
}
