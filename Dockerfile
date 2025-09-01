FROM gradle:8.7-jdk17 as builder
WORKDIR /build

# 그래들 파일이 변경되었을 때만 새롭게 의존패키지 다운로드 받게함.
COPY build.gradle settings.gradle /build/
RUN gradle dependencies --no-daemon

# 빌더 이미지에서 애플리케이션 빌드 (테스트 포함)
COPY . /build
RUN gradle clean build --no-daemon --parallel

FROM openjdk:17-slim
WORKDIR /app

COPY --from=builder /build/server-cloud/build/libs/*.jar ./app.jar
ENV USE_PROFILE dev

ENTRYPOINT ["java", "-Dspring.profiles.active=${USE_PROFILE}", "-jar", "/app/app.jar"]