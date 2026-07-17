package dev.jun.cachesplit.origin.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "origin")
data class OriginProperties(
    /** 메모리에 통째로 적재할 CSV. 키 공간 N 은 이 파일의 행 수다. */
    val csvPath: String,
    /**
     * 미스 페널티 — **측정 대상이 아니라 파라미터**다.
     * 모든 키에 대해 정확히 같아야 arm 간 차이가 순수하게 미스 횟수 차이로만 남는다.
     */
    val delayMillis: Long,
)
