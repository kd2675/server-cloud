FROM gradle:8.7-jdk17 as builder
WORKDIR /build

# 🔥 더 세밀한 의존성 캐싱 (서브프로젝트별)
COPY build.gradle settings.gradle /build/
COPY common-core/build.gradle /build/common-core/
COPY common-database/build.gradle /build/common-database/
COPY common-log/build.gradle /build/common-log/
COPY server-cloud/build.gradle /build/server-cloud/
RUN gradle :server-cloud:dependencies --no-daemon

# 🎯 필요한 소스만 복사 (전체 대신)
COPY common-core/ /build/common-core/
COPY common-database/ /build/common-database/
COPY common-log/ /build/common-log/
COPY server-cloud/ /build/server-cloud/

# 빌드 (기존과 동일)
RUN gradle :server-cloud:clean :server-cloud:build --no-daemon --parallel

FROM openjdk:17-slim
WORKDIR /app

COPY --from=builder /build/server-cloud/build/libs/*.jar ./app.jar
ENV USE_PROFILE dev

ENTRYPOINT ["java", "-Dspring.profiles.active=${USE_PROFILE}", "-jar", "/app/app.jar"]
