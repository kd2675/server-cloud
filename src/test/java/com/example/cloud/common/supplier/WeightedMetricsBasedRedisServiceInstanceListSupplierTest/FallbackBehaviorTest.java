package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.supplier.EurekaWeightedBasedRedisInstanceSupplier;
import com.example.cloud.common.supplier.ExtendedServiceInstanceListSupplier;
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
    @DisplayName("완전한 Redis 연결 실패 시 Eureka 기반 Fallback 동작")
    void testGetWithCompleteRedisFailure() {
        // Given - Redis 완전 실패
        when(reactiveValueOperations.get(anyString()))
                .thenReturn(Mono.error(new RuntimeException("Redis connection timeout")));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then - Eureka에서 3개 인스턴스 반환 (Mock 설정)
        StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    // Eureka Mock이 3개를 반환하므로 3개 기대
                    assertThat(instances).hasSize(3);
                    assertThat(instances.stream()
                            .allMatch(inst -> inst.getServiceId().equals("service-batch")))
                            .isTrue();
                    
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
    @DisplayName("Redis가 null일 때 Eureka 기반 동작")
    void testGetWithNullRedis() {
        // Given - Redis가 완전히 null인 Supplier
        ExtendedServiceInstanceListSupplier supplierWithNullRedis =
                new EurekaWeightedBasedRedisInstanceSupplier(context, discoveryClient, null);

        // When
        Flux<List<ServiceInstance>> result = supplierWithNullRedis.get();

        // Then - Eureka에서 3개 조회
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
    @DisplayName("건강하지 않은 인스턴스들 - Eureka Fallback 동작")
    void testGetWithUnhealthyInstancesFallback() {
        // Given - 모든 인스턴스가 건강하지 않음
        Map<String, Object> unhealthyData = createHealthData(false);

        when(reactiveValueOperations.get(anyString()))
                .thenReturn(Mono.just(unhealthyData));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then - 건강한 인스턴스 없음 -> Eureka에서 3개 조회
        StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    // Eureka Mock이 3개 반환
                    assertThat(instances).hasSize(3);
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
        // Given - 정확한 Redis 키로 Mock 설정
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> metricsData = createMetricsData(30.0, 40.0, 50.0);
        
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-1"))
                .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-1"))
                .thenReturn(Mono.just(metricsData));
        
        // 나머지는 건강하지 않음
        Map<String, Object> unhealthyData = createHealthData(false);
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-2"))
                .thenReturn(Mono.just(unhealthyData));
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-3"))
                .thenReturn(Mono.just(unhealthyData));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    assertThat(instances).isNotEmpty();
                    
                    // service-batch-1만 건강하므로 이것만 포함
                    boolean hasBatch1 = instances.stream()
                            .anyMatch(inst -> "service-batch-1".equals(inst.getInstanceId()));
                    assertThat(hasBatch1).isTrue();
                    
                    // 가중치 계산: 30.0 -> 100.0/30.0 = 3.33 -> 3개
                    long batch1Count = instances.stream()
                            .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                            .count();
                    assertThat(batch1Count).isEqualTo(3);
                    
                    return true;
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Fallback 메서드 직접 테스트 - Redis 없을 때 Eureka 사용")
    void testGetFallbackInstancesDirectly() {
        // Given - Redis 없이 supplier 생성하여 Eureka fallback 로직 확인
        ExtendedServiceInstanceListSupplier supplierWithoutRedis =
                new EurekaWeightedBasedRedisInstanceSupplier(context, discoveryClient, null);
        
        // When
        Flux<List<ServiceInstance>> result = supplierWithoutRedis.get();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    // Eureka Mock이 3개 반환
                    assertThat(instances).hasSize(3);
                    
                    Set<String> expectedIds = Set.of(
                            "service-batch-1", 
                            "service-batch-2", 
                            "service-batch-3"
                    );
                    Set<String> actualIds = instances.stream()
                            .map(ServiceInstance::getInstanceId)
                            .collect(Collectors.toSet());
                    
                    assertThat(actualIds).isEqualTo(expectedIds);
                    
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
                    // Eureka Fallback으로 3개
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