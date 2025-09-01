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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

@DisplayName("가중치 기반 로드밸런싱 테스트")
class WeightedLoadBalancingTest extends WeightedMetricsTestBase {

    @Test
    @DisplayName("건강한 인스턴스들에 대한 가중치 기반 로드밸런싱")
    void testGetWithHealthyInstancesWeightedBalancing() {
        // Given - 다양한 부하 점수의 인스턴스들
        Map<String, Object> health1 = createHealthData(true);
        Map<String, Object> metrics1 = createMetricsData(20.0, 30.0, 40.0); // 낮은 부하 = 높은 가중치

        Map<String, Object> health2 = createHealthData(true);
        Map<String, Object> metrics2 = createMetricsData(80.0, 70.0, 85.0); // 높은 부하 = 낮은 가중치

        Map<String, Object> health3 = createHealthData(true);
        Map<String, Object> metrics3 = createMetricsData(50.0, 60.0, 55.0); // 중간 부하 = 중간 가중치

        setupRedisResponses(health1, metrics1, health2, metrics2, health3, metrics3);

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    assertThat(instances).isNotEmpty();
                    
                    // 가중치 기반으로 낮은 부하의 인스턴스가 더 많이 포함되어야 함
                    long batch1Count = instances.stream()
                            .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                            .count();
                    long batch2Count = instances.stream()
                            .filter(inst -> "service-batch-2".equals(inst.getInstanceId()))
                            .count();
                    long batch3Count = instances.stream()
                            .filter(inst -> "service-batch-3".equals(inst.getInstanceId()))
                            .count();

                    // 부하가 낮은 인스턴스(batch-1)가 가장 많이, 부하가 높은 인스턴스(batch-2)가 가장 적게
                    assertThat(batch1Count).isGreaterThanOrEqualTo(batch3Count);
                    assertThat(batch3Count).isGreaterThanOrEqualTo(batch2Count);

                    return true;
                })
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(doubles = {10.0, 25.0, 50.0, 75.0, 95.0})
    @DisplayName("다양한 부하점수에 대한 가중치 계산")
    void testWeightCalculationForVariousLoadScores(double loadScore) {
        // Given - 특정 부하점수의 인스턴스
        Map<String, Object> healthData = createHealthData(true);
        Map<String, Object> metricsData = createMetricsData(loadScore, 50.0, 60.0);

        when(reactiveValueOperations.get(anyString()))
                .thenReturn(Mono.just(healthData), Mono.just(metricsData));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    assertThat(instances).isNotEmpty();
                    
                    // 낮은 부하점수일수록 더 많은 인스턴스가 생성되어야 함
                    if (loadScore < 30.0) {
                        assertThat(instances.size()).isGreaterThanOrEqualTo(3);
                    }
                    
                    return true;
                })
                .verifyComplete();
    }

    private void setupRedisResponses(Map<String, Object> health1, Map<String, Object> metrics1,
                                   Map<String, Object> health2, Map<String, Object> metrics2,
                                   Map<String, Object> health3, Map<String, Object> metrics3) {
        when(reactiveValueOperations.get(contains("health:service-batch-1")))
                .thenReturn(Mono.just(health1));
        when(reactiveValueOperations.get(contains("metrics:service-batch-1")))
                .thenReturn(Mono.just(metrics1));
        when(reactiveValueOperations.get(contains("health:service-batch-2")))
                .thenReturn(Mono.just(health2));
        when(reactiveValueOperations.get(contains("metrics:service-batch-2")))
                .thenReturn(Mono.just(metrics2));
        when(reactiveValueOperations.get(contains("health:service-batch-3")))
                .thenReturn(Mono.just(health3));
        when(reactiveValueOperations.get(contains("metrics:service-batch-3")))
                .thenReturn(Mono.just(metrics3));
    }
}