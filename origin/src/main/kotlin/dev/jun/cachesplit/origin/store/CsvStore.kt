package dev.jun.cachesplit.origin.store

import dev.jun.cachesplit.origin.config.OriginProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * CSV 를 통째로 메모리에 올려두고 고정 지연 후 반환한다. **실 DB 가 아니다.**
 *
 * 왜 H2 를 안 쓰나 (docs/DECISIONS.md §8):
 *   DB 는 자기 버퍼 캐시를 갖는데 **그 캐시가 arm 마다 다르게 데워진다.**
 *   B 는 미스가 많으니 DB 캐시를 더 넓게 데우고 → B 의 미스가 A 의 미스보다 싸진다
 *   → 우리가 재려는 "B 의 손해"를 DB 가 몰래 깎아준다.
 *   노이즈면 평균으로 없어지지만 이건 **편향**이라 안 없어지고, 하필 방향이
 *   결론을 약하게 만드는 쪽이다.
 *
 * 전부 메모리에 상주 = 모든 키의 미스 비용이 균일 = 미스 페널티가 정확히 우리가 정한 값.
 */
@Component
class CsvStore(private val props: OriginProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private lateinit var data: Map<String, Long>

    /** 시스템 전체의 미스 횟수. 앱들의 miss 합과 일치해야 한다 (교차 검증). */
    val requestCount = AtomicLong(0)

    @PostConstruct
    fun load() {
        val path = Path.of(props.csvPath)
        require(Files.exists(path)) { "CSV 없음: ${props.csvPath}" }

        data = Files.newBufferedReader(path).useLines { lines ->
            lines.drop(1)                          // 헤더
                .filter { it.isNotBlank() }
                .map { line ->
                    val (id, value) = line.split(',', limit = 2)
                    id to value.trim().toLong()
                }
                .toMap()
        }
        log.info("CSV 적재 완료: {} 행, 지연 {}ms, 경로 {}", data.size, props.delayMillis, props.csvPath)
    }

    fun get(id: String): Long? {
        requestCount.incrementAndGet()
        // 미스 페널티. 실제 IO 가 아니라 sleep 이므로 분산이 거의 없다 — 그게 목적이다.
        if (props.delayMillis > 0) Thread.sleep(props.delayMillis)
        return data[id]
    }

    fun size(): Int = data.size
    fun delayMillis(): Long = props.delayMillis
}
