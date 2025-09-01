package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplier;
import com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest.WeightedMetricsTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@DisplayName("Fallback 동작 테스트")
class FallbackBehaviorTest extends WeightedMetricsTestBase {

    @Test
    @DisplayName("완전한 Redis 연결 실패 시 Fallback 동작")
    void testGetWithCompleteRedisFailure() {
        // Given - Redis 완전 실패
        when(reactiveValueOperations.get(anyString()))
                .thenReturn(Mono.error(new RuntimeException("Redis connection timeout")));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    assertThat(instances).hasSize(3);
                    assertThat(instances.stream()
                            .allMatch(inst -> inst.getServiceId().equals("service-batch")))
                            .isTrue();
                    
                    // 인스턴스 ID 확인
                    Set<String> instanceIds = instances.stream()
                            .map(ServiceInstance::getInstanceId)
                            .collect(Collectors.toSet());
                    assertThat(instanceIds).containsExactlyInAnyOrder(
                            "service-batch-1", 
                            "service-batch-2", 
                            "service-batch-3"
                    );
                    
                    return true;
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Redis가 null일 때 Fallback 동작")
    void testGetWithNullRedis() {
        // Given - Redis가 완전히 null인 Supplier
        WeightedMetricsBasedRedisServiceInstanceListSupplier supplierWithNullRedis = 
                new WeightedMetricsBasedRedisServiceInstanceListSupplier(context, null);

        // When
        Flux<List<ServiceInstance>> result = supplierWithNullRedis.get();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    assertThat(instances).hasSize(3);
                    assertThat(instances.stream()
                            .allMatch(inst -> inst.getServiceId().equals("service-batch")))
                            .isTrue();
                    
                    return true;
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("건강하지 않은 인스턴스들 - Fallback 동작")
    void testGetWithUnhealthyInstancesFallback() {
        // Given - 모든 인스턴스가 건강하지 않음
        Map<String, Object> unhealthyData = createHealthData(false);

        when(reactiveValueOperations.get(anyString()))
                .thenReturn(Mono.just(unhealthyData));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    assertThat(instances).hasSize(3); // Fallback으로 모든 인스턴스 반환
                    assertThat(instances.stream()
                            .allMatch(inst -> inst.getServiceId().equals("service-batch")))
                            .isTrue();
                    return true;
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Redis 부분적 실패 시 동작")
    void testGetWithPartialRedisFailure() {
        // Given - 일부는 성공, 일부는 실패
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> metricsData = createMetricsData(30.0, 40.0, 50.0);
        
        when(reactiveValueOperations.get(contains("health:service-batch-1")))
                .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-1")))
                .thenReturn(Mono.just(metricsData));
        
        // 나머지는 실패
        when(reactiveValueOperations.get(contains("health:service-batch-2")))
                .thenReturn(Mono.error(new RuntimeException("Connection lost")));
        when(reactiveValueOperations.get(contains("health:service-batch-3")))
                .thenReturn(Mono.error(new RuntimeException("Connection lost")));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    assertThat(instances).isNotEmpty();
                    
                    // 성공한 인스턴스가 포함되어야 함
                    boolean hasHealthyInstance = instances.stream()
                            .anyMatch(inst -> "service-batch-1".equals(inst.getInstanceId()));
                    assertThat(hasHealthyInstance).isTrue();
                    
                    return true;
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Fallback 메서드 직접 테스트")
    void testGetFallbackInstancesDirectly() {
        // Given - Redis 없이 supplier 생성하여 fallback 로직 확인
        WeightedMetricsBasedRedisServiceInstanceListSupplier supplierWithoutRedis = 
                new WeightedMetricsBasedRedisServiceInstanceListSupplier(context, null);
        
        // When
        Flux<List<ServiceInstance>> result = supplierWithoutRedis.get();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    // Fallback 로직 검증
                    assertThat(instances).hasSize(3);
                    
                    // 모든 인스턴스가 정적 인스턴스와 일치하는지 확인
                    Set<String> expectedIds = Set.of(
                            "service-batch-1", 
                            "service-batch-2", 
                            "service-batch-3"
                    );
                    Set<String> actualIds = instances.stream()
                            .map(ServiceInstance::getInstanceId)
                            .collect(Collectors.toSet());
                    
                    assertThat(actualIds).isEqualTo(expectedIds);
                    
                    // 각 인스턴스의 기본 속성 확인
                    instances.forEach(instance -> {
                        assertThat(instance.getServiceId()).isEqualTo("service-batch");
                        assertThat(instance.getHost()).isEqualTo("localhost");
                        assertThat(instance.getPort()).isIn(8081, 8082, 8083);
                        assertThat(instance.isSecure()).isFalse();
                    });
                    
                    return true;
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("다양한 실패 시나리오에서의 Fallback 동작")
    void testFallbackUnderVariousFailureScenarios() {
        // 시나리오 1: Redis 타임아웃
        when(reactiveValueOperations.get(anyString()))
                .thenReturn(Mono.error(new RuntimeException("Read timeout")));
        
        StepVerifier.create(supplier.get())
                .expectNextMatches(instances -> {
                    assertThat(instances).hasSize(3);
                    return true;
                })
                .verifyComplete();
        
        // 시나리오 2: Redis 네트워크 오류
        when(reactiveValueOperations.get(anyString()))
                .thenReturn(Mono.error(new RuntimeException("Network unreachable")));
        
        StepVerifier.create(supplier.get())
                .expectNextMatches(instances -> {
                    assertThat(instances).hasSize(3);
                    return true;
                })
                .verifyComplete();
        
        // 시나리오 3: Redis 인증 실패
        when(reactiveValueOperations.get(anyString()))
                .thenReturn(Mono.error(new RuntimeException("Authentication failed")));
        
        StepVerifier.create(supplier.get())
                .expectNextMatches(instances -> {
                    assertThat(instances).hasSize(3);
                    return true;
                })
                .verifyComplete();
    }
}