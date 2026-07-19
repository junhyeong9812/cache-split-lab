plugins {
    // 공통 설정은 루트 build.gradle.kts 의 subprojects 블록이 소유한다.
}

dependencies {
    // arm D — 공유 Redis 클라이언트. 버전은 Spring Boot BOM 이 관리한다.
    implementation("io.lettuce:lettuce-core")
}
