package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest.WeightedMetricsTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.client.ServiceInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DisplayName("가중치 기반 로드밸런싱 테스트")
class WeightedLoadBalancingTest extends WeightedMetricsTestBase {

    @Test
    @DisplayName("건강한 인스턴스들에 대한 가중치 기반 로드밸런싱")
    void testGetWithHealthyInstancesWeightedBalancing() {
        // Given - 정확한 Redis 키로 Mock 설정
        Map<String, Object> health1 = createHealthData(true);
        Map<String, Object> metrics1 = createMetricsData(20.0, 30.0, 40.0); // weight 5

        Map<String, Object> health2 = createHealthData(true);
        Map<String, Object> metrics2 = createMetricsData(80.0, 70.0, 85.0); // weight 1.25

        Map<String, Object> health3 = createHealthData(true);
        Map<String, Object> metrics3 = createMetricsData(50.0, 60.0, 55.0); // weight 2

        setupRedisResponses(health1, metrics1, health2, metrics2, health3, metrics3);

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    assertThat(instances).isNotEmpty();
                    
                    // 가중치 계산 검증
                    // batch-1 (20.0): 100.0/20.0 = 5.0 -> 5개
                    // batch-2 (80.0): 100.0/80.0 = 1.25 -> 1개
                    // batch-3 (50.0): 100.0/50.0 = 2.0 -> 2개
                    
                    long batch1Count = instances.stream()
                            .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                            .count();
                    long batch2Count = instances.stream()
                            .filter(inst -> "service-batch-2".equals(inst.getInstanceId()))
                            .count();
                    long batch3Count = instances.stream()
                            .filter(inst -> "service-batch-3".equals(inst.getInstanceId()))
                            .count();

                    assertThat(batch1Count).isEqualTo(5);
                    assertThat(batch2Count).isEqualTo(1);
                    assertThat(batch3Count).isEqualTo(2);

                    return true;
                })
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(doubles = {10.0, 25.0, 50.0, 75.0, 95.0})
    @DisplayName("다양한 부하점수에 대한 가중치 계산")
    void testWeightCalculationForVariousLoadScores(double loadScore) {
        // Given - 정확한 Redis 키로 Mock 설정
        Map<String, Object> healthData = createHealthData(true);
        Map<String, Object> metricsData = createMetricsData(loadScore, 50.0, 60.0);

        when(reactiveValueOperations.get("loadbalancer:health:service-batch-1"))
                .thenReturn(Mono.just(healthData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-1"))
                .thenReturn(Mono.just(metricsData));
        
        // 나머지는 unhealthy
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
                    
                    // 가중치 계산
                    double expectedWeight = 100.0 / Math.max(loadScore, 10.0);
                    expectedWeight = Math.max(1.0, Math.min(10.0, expectedWeight));
                    int expectedCopies = (int) Math.round(expectedWeight);
                    
                    long actualCount = instances.stream()
                            .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                            .count();
                    
                    assertThat(actualCount).isEqualTo(expectedCopies);
                    
                    return true;
                })
                .verifyComplete();
    }

    private void setupRedisResponses(Map<String, Object> health1, Map<String, Object> metrics1,
                                   Map<String, Object> health2, Map<String, Object> metrics2,
                                   Map<String, Object> health3, Map<String, Object> metrics3) {
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-1"))
                .thenReturn(Mono.just(health1));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-1"))
                .thenReturn(Mono.just(metrics1));
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-2"))
                .thenReturn(Mono.just(health2));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-2"))
                .thenReturn(Mono.just(metrics2));
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-3"))
                .thenReturn(Mono.just(health3));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-3"))
                .thenReturn(Mono.just(metrics3));
    }
}