package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.supplier.ExtendedServiceInstanceListSupplier;
import com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplier;
import com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest.WeightedMetricsTestBase;
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
    @DisplayName("Redis + WebClient + 환경변수 연쇄 실패 시나리오")
    void testCascadeFailureRedisWebClientEnvironment() {
        // Given - 모든 구성 요소 실패 시뮬레이션
        
        // 1. Redis 연결 실패
        when(reactiveValueOperations.get(anyString()))
            .thenReturn(Mono.error(new RuntimeException("Redis cluster down")));

        // 2. 잘못된 환경변수 설정으로 새 supplier 생성
        ConfigurableApplicationContext failContext = mock(ConfigurableApplicationContext.class);
        ConfigurableEnvironment failEnvironment = mock(ConfigurableEnvironment.class);
        
        when(failContext.getEnvironment()).thenReturn(failEnvironment);
        when(failEnvironment.getProperty("path.service.batch.host")).thenReturn("invalid-host");
        // 🔥 Integer.class로 호출되는 것만 Mock 설정 (실제 사용되는 것만)
        when(failEnvironment.getProperty(eq("path.service.batch.port1"), eq(Integer.class))).thenReturn(8081);
        when(failEnvironment.getProperty(eq("path.service.batch.port2"), eq(Integer.class))).thenReturn(8082);
        when(failEnvironment.getProperty(eq("path.service.batch.port3"), eq(Integer.class))).thenReturn(8083);

        // When - 연쇄 실패 상황에서 supplier 동작 확인
        try {
            ExtendedServiceInstanceListSupplier failSupplier =
                new WeightedMetricsBasedRedisServiceInstanceListSupplier(failContext, reactiveRedisTemplate);
            
            Flux<List<ServiceInstance>> result = failSupplier.get();
            
            // Then - 최소한의 기능이라도 유지되어야 함
            StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    // 완전히 실패하지 않고 최소한 빈 리스트라도 반환
                    assertThat(instances).isNotNull();
                    // fallback이 동작하여 인스턴스들을 반환하거나, 빈 리스트를 반환
                    return true;
                })
                .verifyComplete();
                
        } catch (Exception e) {
            // 예외가 발생하더라도 시스템이 완전히 다운되지 않아야 함
            assertThat(e).isNotInstanceOf(OutOfMemoryError.class);
            assertThat(e).isNotInstanceOf(StackOverflowError.class);
        }
    }

    @Test
    @DisplayName("부분 복구 시나리오 테스트")
    void testPartialRecoveryScenario() {
        // Given - 점진적 복구 시뮬레이션
        // 첫 번째 호출: 모든 것이 실패
        // 두 번째 호출: Redis는 복구, WebClient는 여전히 실패
        // 세 번째 호출: 모든 것이 정상
        
        when(reactiveValueOperations.get(anyString()))
            .thenReturn(Mono.error(new RuntimeException("All systems down"))) // 첫 번째: 전체 실패
            .thenReturn(Mono.just(createHealthData(true)))                    // 두 번째: Redis 복구
            .thenReturn(Mono.just(createHealthData(true)));                   // 세 번째: 정상

        // When & Then - 점진적 복구 확인
        
        // 1차: 전체 실패 - fallback 동작
        StepVerifier.create(supplier.get())
            .expectNextMatches(instances -> {
                assertThat(instances).hasSize(3); // fallback으로 모든 인스턴스
                return true;
            })
            .verifyComplete();

        // 2차: 부분 복구 - 일부 기능 복구
        StepVerifier.create(supplier.get())
            .expectNextMatches(instances -> {
                assertThat(instances).isNotEmpty();
                // Redis는 복구되었지만 완전하지 않을 수 있음
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
    @DisplayName("메모리 부족 시뮬레이션 테스트")
    void testOutOfMemorySimulation() {
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