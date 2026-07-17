package dev.jun.cachesplit.origin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * origin — 백킹 스토어. 캐시 미스 페널티의 출처.
 *
 * **실제 DB 가 아니다.** CSV 를 통째로 메모리에 올려두고 고정 지연 후 반환한다.
 * 전부 메모리에 상주하므로 미스 비용이 모든 키에 대해 균일하고, 우리가 정한 값 그대로다.
 *
 * 왜 H2 같은 실 DB 를 안 쓰는가 (docs/ARCHITECTURE.md §1):
 *   DB 는 자기 버퍼 캐시를 갖는데, 그 캐시가 arm 마다 다르게 데워진다.
 *   B 는 미스를 많이 내니 DB 캐시를 더 넓게 데우고 → B 의 미스가 A 의 미스보다 싸진다
 *   → 우리가 재려는 "B 의 손해"를 DB 가 몰래 깎아준다.
 *   노이즈면 평균으로 없어지지만 이건 **편향**이라 안 없어지고,
 *   하필 방향이 우리 결론을 약하게 만드는 쪽이다.
 *
 * 미스 페널티는 측정 대상이 아니라 **파라미터**다. 그래야 arm 간 차이가
 * 순수하게 미스 **횟수** 차이로만 남는다.
 */
@SpringBootApplication
class OriginApplication

fun main(args: Array<String>) {
    runApplication<OriginApplication>(*args)
}
