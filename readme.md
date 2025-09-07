# 🌩️ Server-Cloud
Second 프로젝트의 Spring Cloud Gateway 기반 API 게이트웨이 서버
리액티브 프로그래밍 모델을 사용하여 마이크로서비스 간의 통신을 중계

## 📖 프로젝트 개요
Server-Cloud는 Spring Cloud Gateway를 기반으로 한 리액티브 API 게이트웨이
WebFlux를 사용한 비동기 논블로킹 방식으로 높은 성능을 제공
Resilience4J를 통한 Circuit Breaker 패턴과 JWT 기반 인증을 지원

## 🎯 주요 기능
- **API 게이트웨이**: 마이크로서비스 간 통신 중계 및 라우팅
- **헬스 체크**: 서비스 상태 확인 및 모니터링
- **메트릭 수집**: 시스템 및 서비스 메트릭 수집 및 조회
- **로드 밸런싱**: 서비스 간 부하 분산
- **작업 관리**: 실행 중인 작업 목록 조회 및 취소

## 🛠️ 기술 스택
- **Spring WebFlux**: 리액티브 웹 프레임워크
- **Spring Cloud Gateway**: 리액티브 기반 API 게이트웨이
- **Spring Boot Actuator**: 메트릭 및 헬스 체크

- **Circuit Breaker**: Resilience4J를 통한 장애 격리 및 복구
- **리액티브 Redis**: 비동기 Redis 연동을 통한 캐싱 및 세션 관리
- **메트릭 모니터링**: Spring Boot Actuator를 통한 시스템 모니터링
- **라우팅 관리**: 동적 라우팅 및 필터 체인 관리

### 회복성 패턴
- **Resilience4J**: Circuit Breaker, Retry, Rate Limiter
- **Spring Cloud Circuit Breaker**: 리액티브 Circuit Breaker 통합

### 데이터베이스
- **MySQL**: 메인 데이터베이스

## 🚀 주요 API 엔드포인트

### Gateway API
- **POST** `/api/gateway/execute` - 배치 작업 실행
- **POST** `/api/gateway/service` - 서비스 배치 실행
- **GET** `/api/gateway/health` - 헬스 체크

- 구현중.
- **GET** `/api/gateway/status/{requestId}` - 배치 상태 조회
- **POST** `/api/gateway/upload` - 파일 업로드
- **GET** `/api/gateway/jobs` - 작업 목록 조회
- **DELETE** `/api/gateway/jobs/{jobId}` - 작업 취소
- **GET** `/api/gateway/metrics` - 메트릭 조회

### 연동 서비스
- **service-batch**: 배치 작업 실행 서비스
- **server-batch**: 배치 처리 서버
- **server-file**: 파일 관리 서비스
- **server-member**: 회원 관리 서비스

---

## 🔧 설정 및 실행

- server-batch 프로젝트는 반드시 "second" 프로젝트 디렉터리 내부에 위치해야 합니다.
- 예: .../second/server-batch

이 규칙을 지키지 않으면 빌드/실행 및 배포 스크립트가 실패하도록 구성될 수 있습니다.

### 사전 요구사항
- **JDK 17** 이상
- **MySQL 8.0** 이상
- **Gradle 8.x**
```
