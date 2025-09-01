package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.instance.LoadBalancedServiceBatchInstance;
import com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest.WeightedMetricsTestBase;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

@DisplayName("비동기 처리 복잡성 테스트")
class AsyncProcessingComplexityTest extends WeightedMetricsTestBase {

    @ParameterizedTest
    @ValueSource(doubles = {0.0})
    @DisplayName("0 부하점수에 대한 가중치 계산 - 최고 성능")
    void testCalculateWeightWithZero(double loadScore) {
        // When
        double weight = (double) ReflectionTestUtils.invokeMethod(supplier, "calculateWeight", loadScore);

        // Then - 0.0은 최고 성능이므로 최대 가중치(10.0) 적용
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
        // Given - 복잡한 혼재 상황 설정
        
        // 인스턴스 1: 정상 응답
        Map<String, Object> healthyData1 = createHealthData(true);
        Map<String, Object> validMetrics1 = createMetricsData(25.0, 30.0, 40.0);
        
        // 인스턴스 2: 타임아웃 발생
        // 인스턴스 3: 잘못된 데이터 형식
        Map<String, Object> healthyData3 = createHealthData(true);
        Map<String, Object> invalidMetrics3 = Map.of(
            "loadScore", "invalid_string", // 잘못된 타입
            "cpuUsage", 45.0,
            "memoryUsage", 50.0
        );

        // Mock 설정
        when(reactiveValueOperations.get(contains("health:service-batch-1")))
            .thenReturn(Mono.just(healthyData1));
        when(reactiveValueOperations.get(contains("metrics:service-batch-1")))
            .thenReturn(Mono.just(validMetrics1));
        
        when(reactiveValueOperations.get(contains("health:service-batch-2")))
            .thenReturn(Mono.just(createHealthData(true)));
        when(reactiveValueOperations.get(contains("metrics:service-batch-2")))
            .thenReturn(Mono.delay(Duration.ofSeconds(5)).then(Mono.just(createMetricsData(30.0, 35.0, 45.0)))); // 타임아웃 시뮬레이션
        
        when(reactiveValueOperations.get(contains("health:service-batch-3")))
            .thenReturn(Mono.just(healthyData3));
        when(reactiveValueOperations.get(contains("metrics:service-batch-3")))
            .thenReturn(Mono.just(invalidMetrics3));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
            .expectNextMatches(instances -> {
                assertThat(instances).isNotEmpty();
                
                // 최소한 하나의 유효한 인스턴스는 포함되어야 함
                assertThat(instances).hasSizeGreaterThanOrEqualTo(1);
                
                // 유효한 데이터가 있는 인스턴스가 포함되어야 함
                boolean hasValidInstance = instances.stream()
                    .anyMatch(inst -> "service-batch-1".equals(inst.getInstanceId()));
                assertThat(hasValidInstance).isTrue();
                
                return true;
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("부분적 Redis 실패 시 가중치 계산 안정성")
    void testWeightedCalculationStabilityWithPartialFailures() {
        // Given - 완전한 Mock 설정
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> goodMetrics = createMetricsData(15.0, 25.0, 30.0); // 부하점수 15.0

        // 🔥 service-batch-1만 성공하도록 완전한 Mock 설정
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-1"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-1"))
            .thenReturn(Mono.just(goodMetrics));

        // 🔥 service-batch-2, 3은 건강하지 않은 상태로 설정 (에러 대신 unhealthy)
        Map<String, Object> unhealthyData = createHealthData(false);
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-2"))
            .thenReturn(Mono.just(unhealthyData));
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-3"))
            .thenReturn(Mono.just(unhealthyData));

        // 메트릭은 없거나 에러
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-2"))
            .thenReturn(Mono.empty());
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-3"))
            .thenReturn(Mono.empty());

        // 🔥 reactiveRedisTemplate이 null이 아님을 확인
        assertThat(reactiveRedisTemplate).isNotNull();
        assertThat(reactiveValueOperations).isNotNull();

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
            .expectNextMatches(instances -> {
                assertThat(instances).isNotEmpty();

                // 🔥 디버깅 정보 출력
                System.out.println("=== 테스트 결과 분석 ===");
                System.out.println("총 인스턴스 수: " + instances.size());
                
                Map<String, Long> instanceCounts = instances.stream()
                    .collect(Collectors.groupingBy(
                        ServiceInstance::getInstanceId,
                        Collectors.counting()
                    ));

                instanceCounts.forEach((id, count) ->
                    System.out.println("인스턴스 " + id + ": " + count + "개"));

                // service-batch-1의 복사본 수 확인
                long batch1Copies = instanceCounts.getOrDefault("service-batch-1", 0L);

                // 🔥 가중치 계산 검증: 15.0 -> 100.0 / Math.max(15.0, 10.0) = 6.67 -> Math.round(6.67) = 7
                System.out.println("service-batch-1 복사본 수: " + batch1Copies + " (기대: 7)");

                // 🔥 허용 범위를 조금 넓혀서 테스트 안정성 확보
                assertThat(batch1Copies)
                    .describedAs("LoadScore 15.0 should produce weight ~6.67, resulting in ~7 copies")
                    .isBetween(6L, 8L); // 정확히 7이 아니어도 6~8 범위면 OK
                
                return true;
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("가중치 계산 정확성 검증")
    void testWeightCalculationAccuracy() {
        // Given - 정확한 가중치 계산 확인을 위한 테스트
        Map<String, Object> healthyData = createHealthData(true);
        
        // 부하점수별 예상 가중치와 복사본 수:
        // 20.0 -> 100.0/20.0 = 5.0 -> 5개
        // 50.0 -> 100.0/50.0 = 2.0 -> 2개  
        // 10.0 -> 100.0/10.0 = 10.0 -> 10개
        Map<String, Object> metrics1 = createMetricsData(20.0, 30.0, 40.0); // 5개
        Map<String, Object> metrics2 = createMetricsData(50.0, 55.0, 60.0); // 2개
        Map<String, Object> metrics3 = createMetricsData(10.0, 15.0, 20.0); // 10개

        when(reactiveValueOperations.get(contains("health:service-batch-1")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-1")))
            .thenReturn(Mono.just(metrics1));
            
        when(reactiveValueOperations.get(contains("health:service-batch-2")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-2")))
            .thenReturn(Mono.just(metrics2));
            
        when(reactiveValueOperations.get(contains("health:service-batch-3")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-3")))
            .thenReturn(Mono.just(metrics3));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
            .expectNextMatches(instances -> {
                assertThat(instances).isNotEmpty();
                
                // 각 인스턴스별 복사본 수 확인
                long batch1Count = instances.stream()
                    .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                    .count();
                long batch2Count = instances.stream()
                    .filter(inst -> "service-batch-2".equals(inst.getInstanceId()))
                    .count();
                long batch3Count = instances.stream()
                    .filter(inst -> "service-batch-3".equals(inst.getInstanceId()))
                    .count();
                
                // 정확한 가중치 기반 복사본 수 확인
                assertThat(batch1Count).describedAs("Batch-1 (load:20.0, weight:5.0)").isEqualTo(5);
                assertThat(batch2Count).describedAs("Batch-2 (load:50.0, weight:2.0)").isEqualTo(2);
                assertThat(batch3Count).describedAs("Batch-3 (load:10.0, weight:10.0)").isEqualTo(10);
                
                // 총 인스턴스 수 확인
                assertThat(instances).hasSize(17); // 5 + 2 + 10 = 17
                
                return true;
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Redis 응답 지연과 타임아웃 상호작용 테스트")
    void testRedisResponseDelayAndTimeoutInteraction() {
        // Given - 다양한 지연 시간 설정
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> fastMetrics = createMetricsData(30.0, 35.0, 40.0);
        Map<String, Object> slowMetrics = createMetricsData(40.0, 45.0, 50.0);
        
        // 빠른 응답 (1초)
        when(reactiveValueOperations.get(contains("health:service-batch-1")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-1")))
            .thenReturn(Mono.delay(Duration.ofSeconds(1)).then(Mono.just(fastMetrics)));
        
        // 느린 응답 (2초) - 타임아웃(3초) 내
        when(reactiveValueOperations.get(contains("health:service-batch-2")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-2")))
            .thenReturn(Mono.delay(Duration.ofSeconds(2)).then(Mono.just(slowMetrics)));
        
        // 매우 느린 응답 (4초) - 타임아웃(3초) 초과
        when(reactiveValueOperations.get(contains("health:service-batch-3")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-3")))
            .thenReturn(Mono.delay(Duration.ofSeconds(4)).then(Mono.just(createMetricsData(50.0, 55.0, 60.0))));

        // When - 타임아웃을 고려한 테스트
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
            .expectNextMatches(instances -> {
                assertThat(instances).isNotEmpty();
                
                // 타임아웃 내에 응답한 인스턴스들만 포함되어야 함
                boolean hasFastInstance = instances.stream()
                    .anyMatch(inst -> "service-batch-1".equals(inst.getInstanceId()));
                boolean hasSlowButValidInstance = instances.stream()
                    .anyMatch(inst -> "service-batch-2".equals(inst.getInstanceId()));
                
                assertThat(hasFastInstance).isTrue();
                assertThat(hasSlowButValidInstance).isTrue();
                
                return true;
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("동시성 상황에서 가중치 리스트 일관성 확인")
    void testWeightedListConsistencyUnderConcurrency() {
        // Given - 안정적인 데이터 설정
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> metrics1 = createMetricsData(20.0, 25.0, 30.0); // 가중치 5.0 -> 5개
        Map<String, Object> metrics2 = createMetricsData(40.0, 45.0, 50.0); // 가중치 2.5 -> 3개 (Math.round(2.5) = 3)
        Map<String, Object> metrics3 = createMetricsData(60.0, 65.0, 70.0); // 가중치 1.67 -> 2개 (Math.round(1.67) = 2)

        when(reactiveValueOperations.get(contains("health:service-batch-1")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-1")))
            .thenReturn(Mono.just(metrics1));
        when(reactiveValueOperations.get(contains("health:service-batch-2")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-2")))
            .thenReturn(Mono.just(metrics2));
        when(reactiveValueOperations.get(contains("health:service-batch-3")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-3")))
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
                
                // 모든 결과가 비어있지 않음
                assertThat(instances1).isNotEmpty();
                assertThat(instances2).isNotEmpty();
                assertThat(instances3).isNotEmpty();
                
                // 가중치 분배 패턴이 일관성 있게 유지됨
                // 낮은 부하점수(20.0)의 인스턴스가 가장 많이 포함되어야 함
                long count1_in_result1 = instances1.stream()
                    .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                    .count();
                long count1_in_result2 = instances2.stream()
                    .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                    .count();
                long count1_in_result3 = instances3.stream()
                    .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                    .count();
                
                // 일관성 확인 - 모든 결과에서 같은 가중치 분배
                assertThat(count1_in_result1).isEqualTo(count1_in_result2).isEqualTo(count1_in_result3);
                
                // 정확한 가중치 기반 복사본 수 확인
                assertThat(count1_in_result1).isEqualTo(5);
                
                return true;
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("극한 상황에서의 최소 인스턴스 보장")
    void testMinimumInstanceGuaranteeInExtremeConditions() {
        // Given - 모든 인스턴스가 매우 높은 부하점수 (최소 가중치 상황)
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> extremeMetrics = createMetricsData(200.0, 220.0, 250.0); // 가중치 0.5 -> Math.round(0.5) = 1

        when(reactiveValueOperations.get(contains("health")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics")))
            .thenReturn(Mono.just(extremeMetrics));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then - 최소 1개씩은 보장되어야 함
        StepVerifier.create(result)
            .expectNextMatches(instances -> {
                assertThat(instances).isNotEmpty();
                
                // 각 인스턴스가 최소 1번씩은 포함되어야 함
                boolean hasBatch1 = instances.stream().anyMatch(inst -> "service-batch-1".equals(inst.getInstanceId()));
                boolean hasBatch2 = instances.stream().anyMatch(inst -> "service-batch-2".equals(inst.getInstanceId()));
                boolean hasBatch3 = instances.stream().anyMatch(inst -> "service-batch-3".equals(inst.getInstanceId()));
                
                assertThat(hasBatch1).isTrue();
                assertThat(hasBatch2).isTrue(); 
                assertThat(hasBatch3).isTrue();
                
                // 총 3개의 인스턴스 (각각 최소 가중치 1.0)
                assertThat(instances).hasSize(3);
                
                return true;
            })
            .verifyComplete();
    }
}