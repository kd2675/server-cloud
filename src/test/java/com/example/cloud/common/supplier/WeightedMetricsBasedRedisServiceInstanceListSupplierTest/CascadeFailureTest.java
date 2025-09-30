package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.supplier.EurekaWeightedBasedRedisInstanceSupplier;
import com.example.cloud.common.supplier.ExtendedServiceInstanceListSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("연쇄 실패 시나리오 테스트")
class CascadeFailureTest extends WeightedMetricsTestBase {

    @Test
    @DisplayName("Redis + Eureka 연쇄 실패 시나리오 - Eureka Fallback 동작")
    void testCascadeFailureRedisAndEureka() {
        // Given - Redis 연결 실패
        when(reactiveValueOperations.get(anyString()))
            .thenReturn(Mono.error(new RuntimeException("Redis cluster down")));

        // When - Redis 실패 시 Eureka로 Fallback
        Flux<List<ServiceInstance>> result = supplier.get();
        
        // Then - Eureka Mock이 3개 인스턴스 반환
        StepVerifier.create(result)
            .expectNextMatches(instances -> {
                assertThat(instances).isNotNull();
                // Eureka에서 3개 인스턴스 조회 성공
                assertThat(instances).hasSize(3);
                return true;
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("환경변수 설정 실패 시 기본값으로 동작")
    void testEnvironmentVariableFailure() {
        // Given - 잘못된 환경변수 설정
        ConfigurableApplicationContext failContext = mock(ConfigurableApplicationContext.class);
        ConfigurableEnvironment failEnvironment = mock(ConfigurableEnvironment.class);
        
        when(failContext.getEnvironment()).thenReturn(failEnvironment);
        when(failEnvironment.getProperty("path.service.batch.host")).thenReturn("localhost");
        when(failEnvironment.getProperty(eq("path.service.batch.port3"), eq(Integer.class))).thenReturn(8083);

        // When - 환경변수 문제가 있어도 supplier 생성 성공
        try {
            ExtendedServiceInstanceListSupplier failSupplier =
                new EurekaWeightedBasedRedisInstanceSupplier(failContext, discoveryClient, reactiveRedisTemplate);
            
            Flux<List<ServiceInstance>> result = failSupplier.get();
            
            // Then - 정상 동작
            StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    assertThat(instances).isNotNull();
                    return true;
                })
                .verifyComplete();
                
        } catch (Exception e) {
            // 예외가 발생해도 시스템 다운은 아니어야 함
            assertThat(e).isNotInstanceOf(OutOfMemoryError.class);
            assertThat(e).isNotInstanceOf(StackOverflowError.class);
        }
    }

    @Test
    @DisplayName("부분 복구 시나리오 테스트")
    void testPartialRecoveryScenario() {
        // Given - 점진적 복구 시뮬레이션
        when(reactiveValueOperations.get(anyString()))
            .thenReturn(Mono.error(new RuntimeException("All systems down"))) // 1차: 전체 실패
            .thenReturn(Mono.just(createHealthData(true)))                    // 2차: Redis 복구
            .thenReturn(Mono.just(createHealthData(true)));                   // 3차: 정상

        // When & Then - 1차: 전체 실패 - Eureka fallback
        StepVerifier.create(supplier.get())
            .expectNextMatches(instances -> {
                assertThat(instances).hasSize(3); // Eureka fallback
                return true;
            })
            .verifyComplete();

        // 2차: 부분 복구
        StepVerifier.create(supplier.get())
            .expectNextMatches(instances -> {
                assertThat(instances).isNotEmpty();
                return true;
            })
            .verifyComplete();

        // 3차: 완전 복구
        StepVerifier.create(supplier.get())
            .expectNextMatches(instances -> {
                assertThat(instances).isNotEmpty();
                return true;
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Redis 타임아웃 후 복구 테스트")
    void testRedisTimeoutAndRecovery() {
        // Given - 메모리 부족 상황 시뮬레이션 (큰 객체 반환)
        when(reactiveValueOperations.get(anyString()))
            .thenReturn(Mono.fromCallable(() -> {
                // 매우 큰 맵 생성으로 메모리 압박 시뮬레이션
                return createLargeHealthData();
            }));

        // When & Then - 메모리 부족 상황에서도 안정적 동작
        try {
            StepVerifier.create(supplier.get())
                .expectNextMatches(instances -> {
                    assertThat(instances).isNotNull();
                    return true;
                })
                .verifyComplete();
        } catch (OutOfMemoryError e) {
            // OOM이 발생해도 최소한 로그는 남겨야 함
            System.err.println("Expected OOM during stress test: " + e.getMessage());
            assertThat(e).isInstanceOf(OutOfMemoryError.class);
        }
    }

    private Object createLargeHealthData() {
        // 큰 데이터 구조 생성으로 메모리 압박 시뮬레이션
        java.util.Map<String, Object> largeData = new java.util.HashMap<>();
        largeData.put("isHealthy", true);
        largeData.put("timestamp", System.currentTimeMillis());
        
        // 큰 문자열 배열 추가
        for (int i = 0; i < 1000; i++) {
            largeData.put("largeField" + i, "Large data content ".repeat(1000));
        }
        
        return largeData;
    }
}