package dev.jun.cachesplit.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * SUT 노드 — 캐시를 들고 있는 애플리케이션.
 *
 * 이 산출물 하나를 세 arm 이 공유한다:
 *   arm A → 이 앱 1개 (캐시 N)
 *   arm B → 이 앱 2개 (캐시 N/2 씩) + nginx 라운드로빈
 *   arm C → 이 앱 2개 (캐시 N/2 씩) + nginx 키 해시
 *
 * arm 간에 다른 것은 replica 수 · 캐시 크기 · nginx 라우팅뿐이다.
 * 코드가 arm 마다 갈라지는 순간 "차이는 배치뿐"이라는 최상위 불변식이 깨지고
 * 실험 전체가 무효가 된다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class CacheSplitApplication

fun main(args: Array<String>) {
    runApplication<CacheSplitApplication>(*args)
}
