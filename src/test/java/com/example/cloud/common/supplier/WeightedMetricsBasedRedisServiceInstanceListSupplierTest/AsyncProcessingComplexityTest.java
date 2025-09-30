package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("비동기 처리 복잡성 테스트")
class AsyncProcessingComplexityTest extends WeightedMetricsTestBase {

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 5.0, 10.0})
    @DisplayName("10.0 이하 부하점수는 Math.max 로직에 의해 10.0으로 치환되어 최대 가중치 적용")
    void testCalculateWeightWithLowLoadScores(double loadScore) {
        // When
        double weight = (double) ReflectionTestUtils.invokeMethod(supplier, "calculateWeight", loadScore);

        // Then - Math.max(loadScore, 10.0)로 인해 10.0 사용 -> 100.0/10.0 = 10.0
        assertThat(weight).isEqualTo(10.0);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, -100.0})
    @DisplayName("음수 부하점수에 대한 가중치 계산 - 비정상값")
    void testCalculateWeightWithNegativeValues(double loadScore) {
        // When
        double weight = (double) ReflectionTestUtils.invokeMethod(supplier, "calculateWeight", loadScore);

        // Then - 음수는 비정상 값이므로 최소 가중치(1.0) 적용
        assertThat(weight).isEqualTo(1.0);
    }

    @Test
    @DisplayName("무한대 부하점수에 대한 가중치 계산")
    void testCalculateWeightWithInfinity() {
        // When - 양의 무한대
        double weightPositiveInf = (double) ReflectionTestUtils.invokeMethod(supplier, "calculateWeight", Double.POSITIVE_INFINITY);
        
        // When - 음의 무한대
        double weightNegativeInf = (double) ReflectionTestUtils.invokeMethod(supplier, "calculateWeight", Double.NEGATIVE_INFINITY);

        // Then - 무한대는 비정상 값이므로 최소 가중치(1.0) 적용
        assertThat(weightPositiveInf).isEqualTo(1.0);
        assertThat(weightNegativeInf).isEqualTo(1.0);
    }

    @Test
    @DisplayName("혼재된 Redis 응답 상황에서 가중치 리스트 생성")
    void testCreateWeightedInstanceListWithMixedRedisResponses() {
        // Given - 정확한 Redis 키로 Mock 설정
        
        // 인스턴스 1: 정상 응답
        Map<String, Object> healthyData1 = createHealthData(true);
        Map<String, Object> validMetrics1 = createMetricsData(25.0, 30.0, 40.0);
        
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-1"))
            .thenReturn(Mono.just(healthyData1));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-1"))
            .thenReturn(Mono.just(validMetrics1));
        
        // 인스턴스 2: 타임아웃 발생 (3초 초과)
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-2"))
            .thenReturn(Mono.just(createHealthData(true)));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-2"))
            .thenReturn(Mono.delay(Duration.ofSeconds(5)).then(Mono.just(createMetricsData(30.0, 35.0, 45.0))));
        
        // 인스턴스 3: 잘못된 데이터 형식
        Map<String, Object> healthyData3 = createHealthData(true);
        Map<String, Object> invalidMetrics3 = Map.of(
            "loadScore", "invalid_string", // 잘못된 타입
            "cpuUsage", 45.0,
            "memoryUsage", 50.0
        );
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-3"))
            .thenReturn(Mono.just(healthyData3));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-3"))
            .thenReturn(Mono.just(invalidMetrics3));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
            .expectNextMatches(instances -> {
                assertThat(instances).isNotEmpty();
                
                // service-batch-1은 정상이므로 반드시 포함
                boolean hasBatch1 = instances.stream()
                    .anyMatch(inst -> "service-batch-1".equals(inst.getInstanceId()));
                assertThat(hasBatch1).isTrue();
                
                // 가중치 계산 검증: loadScore 25.0 -> 100.0/25.0 = 4.0 -> 4개
                long batch1Count = instances.stream()
                    .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                    .count();
                assertThat(batch1Count).isEqualTo(4);
                
                // 타임아웃과 잘못된 데이터는 100.0 (기본값)으로 처리 -> weight 1.0
                // 총 3개 인스턴스: 4(batch-1) + 1(batch-2, 100.0) + 1(batch-3, 100.0) = 6
                assertThat(instances).hasSize(6);
                
                return true;
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("부분적 Redis 실패 시 가중치 계산 안정성")
    void testWeightedCalculationStabilityWithPartialFailures() {
        // Given - 정확한 Redis 키로 Mock 설정
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> goodMetrics = createMetricsData(15.0, 25.0, 30.0);

        when(reactiveValueOperations.get("loadbalancer:health:service-batch-1"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-1"))
            .thenReturn(Mono.just(goodMetrics));

        // service-batch-2, 3은 건강하지 않은 상태
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
                Map<String, Long> instanceCounts = instances.stream()
                    .collect(Collectors.groupingBy(
                        ServiceInstance::getInstanceId,
                        Collectors.counting()
                    ));

                long batch1Copies = instanceCounts.getOrDefault("service-batch-1", 0L);

                // 가중치 계산: 15.0 -> 100.0/15.0 = 6.67 -> Math.round(6.67) = 7
                assertThat(batch1Copies)
                    .describedAs("LoadScore 15.0 should produce weight ~6.67, resulting in 7 copies")
                    .isEqualTo(7);
                
                return true;
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("가중치 계산 정확성 검증")
    void testWeightCalculationAccuracy() {
        // Given - 정확한 Redis 키로 Mock 설정
        Map<String, Object> healthyData = createHealthData(true);
        
        Map<String, Object> metrics1 = createMetricsData(20.0, 30.0, 40.0); // 100.0/20.0 = 5.0
        Map<String, Object> metrics2 = createMetricsData(50.0, 55.0, 60.0); // 100.0/50.0 = 2.0
        Map<String, Object> metrics3 = createMetricsData(10.0, 15.0, 20.0); // 100.0/10.0 = 10.0

        when(reactiveValueOperations.get("loadbalancer:health:service-batch-1"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-1"))
            .thenReturn(Mono.just(metrics1));
            
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-2"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-2"))
            .thenReturn(Mono.just(metrics2));
            
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-3"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-3"))
            .thenReturn(Mono.just(metrics3));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
            .expectNextMatches(instances -> {
                assertThat(instances).isNotEmpty();
                
                long batch1Count = instances.stream()
                    .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                    .count();
                long batch2Count = instances.stream()
                    .filter(inst -> "service-batch-2".equals(inst.getInstanceId()))
                    .count();
                long batch3Count = instances.stream()
                    .filter(inst -> "service-batch-3".equals(inst.getInstanceId()))
                    .count();
                
                assertThat(batch1Count).describedAs("Batch-1 (load:20.0, weight:5.0)").isEqualTo(5);
                assertThat(batch2Count).describedAs("Batch-2 (load:50.0, weight:2.0)").isEqualTo(2);
                assertThat(batch3Count).describedAs("Batch-3 (load:10.0, weight:10.0)").isEqualTo(10);
                
                assertThat(instances).hasSize(17); // 5 + 2 + 10 = 17
                
                return true;
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Redis 응답 지연과 타임아웃 상호작용 테스트")
    void testRedisResponseDelayAndTimeoutInteraction() {
        // Given - 정확한 Redis 키로 Mock 설정
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> fastMetrics = createMetricsData(30.0, 35.0, 40.0);
        Map<String, Object> slowMetrics = createMetricsData(40.0, 45.0, 50.0);
        
        // 빠른 응답 (1초)
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-1"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-1"))
            .thenReturn(Mono.delay(Duration.ofSeconds(1)).thenReturn(Mono.just(fastMetrics)).flatMap(m -> m));
        
        // 느린 응답 (2초) - 타임아웃(3초) 내
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-2"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-2"))
            .thenReturn(Mono.delay(Duration.ofSeconds(2)).thenReturn(Mono.just(slowMetrics)).flatMap(m -> m));
        
        // 매우 느린 응답 (4초) - 타임아웃(3초) 초과 -> onErrorReturn(100.0)
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-3"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-3"))
            .thenReturn(Mono.delay(Duration.ofSeconds(4)).then(Mono.just(createMetricsData(50.0, 55.0, 60.0))));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
            .expectNextMatches(instances -> {
                assertThat(instances).isNotEmpty();
                
                // batch-1 (30.0): weight = 100.0/30.0 = 3.33 -> 3개
                // batch-2 (40.0): weight = 100.0/40.0 = 2.5 -> 3개
                // batch-3 (타임아웃, 100.0): weight = 1.0 -> 1개
                
                long batch1Count = instances.stream()
                    .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                    .count();
                long batch2Count = instances.stream()
                    .filter(inst -> "service-batch-2".equals(inst.getInstanceId()))
                    .count();
                long batch3Count = instances.stream()
                    .filter(inst -> "service-batch-3".equals(inst.getInstanceId()))
                    .count();
                
                // 가중치 계산 검증
                assertThat(batch1Count).isEqualTo(3); // 100.0/30.0 = 3.33 -> 3
                assertThat(batch2Count).isEqualTo(3); // 100.0/40.0 = 2.5 -> 3
                assertThat(batch3Count).isEqualTo(1); // 타임아웃 -> 100.0 -> weight 1.0
                
                return true;
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("동시성 상황에서 가중치 리스트 일관성 확인")
    void testWeightedListConsistencyUnderConcurrency() {
        // Given - 정확한 Redis 키로 Mock 설정
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> metrics1 = createMetricsData(20.0, 25.0, 30.0); // 5.0 -> 5개
        Map<String, Object> metrics2 = createMetricsData(40.0, 45.0, 50.0); // 2.5 -> 3개
        Map<String, Object> metrics3 = createMetricsData(60.0, 65.0, 70.0); // 1.67 -> 2개

        when(reactiveValueOperations.get("loadbalancer:health:service-batch-1"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-1"))
            .thenReturn(Mono.just(metrics1));
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-2"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-2"))
            .thenReturn(Mono.just(metrics2));
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-3"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-3"))
            .thenReturn(Mono.just(metrics3));

        // When - 동시에 여러 번 호출
        Flux<List<ServiceInstance>> result1 = supplier.get();
        Flux<List<ServiceInstance>> result2 = supplier.get();
        Flux<List<ServiceInstance>> result3 = supplier.get();

        // Then - 모든 결과가 일관성 있게 생성되어야 함
        StepVerifier.create(Flux.zip(result1, result2, result3))
            .expectNextMatches(results -> {
                List<ServiceInstance> instances1 = results.getT1();
                List<ServiceInstance> instances2 = results.getT2();
                List<ServiceInstance> instances3 = results.getT3();
                
                assertThat(instances1).isNotEmpty();
                assertThat(instances2).isNotEmpty();
                assertThat(instances3).isNotEmpty();
                
                long count1_in_result1 = instances1.stream()
                    .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                    .count();
                long count1_in_result2 = instances2.stream()
                    .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                    .count();
                long count1_in_result3 = instances3.stream()
                    .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                    .count();
                
                // 일관성 확인
                assertThat(count1_in_result1).isEqualTo(count1_in_result2).isEqualTo(count1_in_result3);
                assertThat(count1_in_result1).isEqualTo(5);
                
                return true;
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("극한 상황에서의 최소 인스턴스 보장")
    void testMinimumInstanceGuaranteeInExtremeConditions() {
        // Given - 모든 인스턴스가 매우 높은 부하점수
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> extremeMetrics = createMetricsData(200.0, 220.0, 250.0);

        when(reactiveValueOperations.get("loadbalancer:health:service-batch-1"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-1"))
            .thenReturn(Mono.just(extremeMetrics));
        
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-2"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-2"))
            .thenReturn(Mono.just(extremeMetrics));
        
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-3"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-3"))
            .thenReturn(Mono.just(extremeMetrics));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
            .expectNextMatches(instances -> {
                assertThat(instances).isNotEmpty();
                
                // 각 인스턴스가 최소 1번씩은 포함되어야 함
                // loadScore 200.0 -> baseWeight = 100.0/200.0 = 0.5
                // Math.max(1.0, Math.min(10.0, 0.5)) = 1.0
                // Math.round(1.0) = 1
                
                long batch1Count = instances.stream()
                    .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                    .count();
                long batch2Count = instances.stream()
                    .filter(inst -> "service-batch-2".equals(inst.getInstanceId()))
                    .count();
                long batch3Count = instances.stream()
                    .filter(inst -> "service-batch-3".equals(inst.getInstanceId()))
                    .count();
                
                assertThat(batch1Count).isEqualTo(1);
                assertThat(batch2Count).isEqualTo(1);
                assertThat(batch3Count).isEqualTo(1);
                
                assertThat(instances).hasSize(3);
                
                return true;
            })
            .verifyComplete();
    }
}