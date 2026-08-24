# syntax=docker/dockerfile:1
#
# queue-platform 서비스 이미지 — 모듈 하나를 골라 굽는다.
#
#   docker build --build-arg MODULE=queue-api      --build-arg APP_PORT=8080 -t queue-api .
#   docker build --build-arg MODULE=queue-batch    --build-arg APP_PORT=8081 -t queue-batch .
#   docker build --build-arg MODULE=queue-consumer --build-arg APP_PORT=8082 -t queue-consumer .
#
# 세 앱이 같은 Gradle 멀티모듈에서 나오고 실행 방식도 같아서 Dockerfile을 셋으로 나누지 않는다.
# 나누면 base 이미지·JVM 옵션·유저 설정이 세 곳에서 갈린다.

ARG JAVA_VERSION=21

# ─────────────────────────────── build ───────────────────────────────
FROM eclipse-temurin:${JAVA_VERSION}-jdk AS build
WORKDIR /src

# 소스 전체를 넣고 이미지 안에서 빌드한다. 호스트에서 구운 jar를 COPY하면 빌드한 JDK와
# 이미지의 JRE가 갈릴 수 있고, CI가 JDK를 따로 갖춰야 한다.
COPY . .

ARG MODULE
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :${MODULE}:bootJar -x test \
    # bootJar와 함께 *-plain.jar(라이브러리용)도 나온다. 와일드카드로 COPY하면 둘이 잡혀
    # 빌드가 깨지므로 여기서 실행 가능한 쪽만 골라 이름을 고정한다.
 && cp "$(find /src/${MODULE}/build/libs -name '*.jar' ! -name '*-plain.jar' | head -1)" /app.jar

# ────────────────────────────── runtime ──────────────────────────────
FROM eclipse-temurin:${JAVA_VERSION}-jre

# root로 돌지 않는다. UID를 고정하는 이유는 k8s의 runAsUser/fsGroup과 볼륨 권한을
# 이미지 밖에서 맞출 수 있어야 하기 때문이다.
RUN useradd --system --uid 10001 queue

WORKDIR /app
COPY --from=build --chown=10001:10001 /app.jar app.jar
USER 10001

# 프로필 기본값은 prod다. local은 127.0.0.1을 가리켜 컨테이너 안에서 성립하지 않는다.
# prod 프로필의 접속 정보는 전부 환경변수다(DB_MASTER_HOST, REDIS_CLUSTER1_NODES 등).
#
# JAVA_TOOL_OPTIONS는 JVM이 스스로 읽으므로 ENTRYPOINT에 셸을 끼울 필요가 없다.
# 셸을 끼우면 PID 1이 셸이 되어 SIGTERM이 JVM에 바로 닿지 않는다 — graceful shutdown이
# 중요한 앱이다(BatchProcessor가 SIGTERM 시점의 drain을 보장한다).
#   MaxRAMPercentage: 컨테이너 메모리 한도 기준. 고정 -Xmx는 한도를 바꿀 때마다 이미지를 다시 굽는다.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

ARG APP_PORT=8080
EXPOSE ${APP_PORT}

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
