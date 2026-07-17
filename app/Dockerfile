# 사전 빌드된 부트 jar 를 담기만 한다 (`./gradlew build` 를 먼저 돌린다).
# 컨테이너 안에서 Gradle 을 돌리지 않는 이유: arm 을 바꿔 띄울 때마다
# 재빌드가 일어나면 "세 arm 이 같은 산출물을 쓴다"를 보장하기 어려워진다.
# 한 번 빌드한 jar 를 셋이 그대로 공유해야 한다.
FROM eclipse-temurin:21-jre

COPY build/libs/app.jar /app.jar

# 힙은 컨테이너 리밋에 맞춰 JVM 이 알아서 잡게 둔다(-XX:MaxRAMPercentage 기본 동작).
# 캐시 크기는 바이트가 아니라 **엔트리 개수**로 강제하므로(ARCHITECTURE §3)
# 메모리 리밋은 실험의 메커니즘이 아니라 안전망이다.
ENTRYPOINT ["java", "-jar", "/app.jar"]
