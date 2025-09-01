package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest.WeightedMetricsTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

@DisplayName("동시성 및 데이터 일관성 테스트")
class ConcurrencyAndConsistencyTest extends WeightedMetricsTestBase {

    @Test
    @DisplayName("멀티 스레드 동시 접근 시 일관성 보장 테스트")
    void testConcurrentAccessConsistency() throws InterruptedException {
        // Given - 안정적인 Redis 응답 설정
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> lowLoadMetrics = createMetricsData(20.0, 25.0, 30.0);
        Map<String, Object> midLoadMetrics = createMetricsData(50.0, 55.0, 60.0);
        Map<String, Object> highLoadMetrics = createMetricsData(80.0, 85.0, 90.0);

        setupStableRedisResponses(healthyData, lowLoadMetrics, midLoadMetrics, highLoadMetrics);

        // When - 동시에 여러 스레드에서 접근
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Exception> errorRef = new AtomicReference<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    Flux<List<ServiceInstance>> result = supplier.get();
                    
                    StepVerifier.create(result)
                        .expectNextMatches(instances -> {
                            // 기본 일관성 검증
                            assertThat(instances).isNotEmpty();
                            
                            // 가중치 분배 일관성 확인 (낮은 부하의 인스턴스가 더 많이 포함됨)
                            long lowLoadInstanceCount = instances.stream()
                                .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                                .count();
                            long highLoadInstanceCount = instances.stream()
                                .filter(inst -> "service-batch-3".equals(inst.getInstanceId()))
                                .count();
                            
                            assertThat(lowLoadInstanceCount).isGreaterThanOrEqualTo(highLoadInstanceCount);
                            
                            successCount.incrementAndGet();
                            return true;
                        })
                        .verifyComplete();
                        
                } catch (Exception e) {
                    errorRef.set(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Then
        latch.await();
        executor.shutdown();
        
        assertThat(errorRef.get()).isNull();
        assertThat(successCount.get()).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("Redis 데이터 변경 시 즉시 반영 테스트")
    void testImmediateReflectionOfRedisDataChanges() {
        // Given - 초기 데이터 설정
        Map<String, Object> healthyData = createHealthData(true);
        AtomicReference<Map<String, Object>> metricsDataRef = new AtomicReference<>(
            createMetricsData(80.0, 85.0, 90.0) // 높은 부하
        );

        when(reactiveValueOperations.get(contains("health:service-batch-1")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-1")))
            .thenAnswer(inv -> Mono.just(metricsDataRef.get()));

        setupOtherInstancesWithHighLoad(healthyData);

        // When & Then - 첫 번째: 높은 부하 상태
        StepVerifier.create(supplier.get())
            .expectNextMatches(instances -> {
                // 높은 부하로 인해 적은 가중치
                long instanceCount = instances.stream()
                    .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                    .count();
                assertThat(instanceCount).isLessThanOrEqualTo(2); // 낮은 가중치
                return true;
            })
            .verifyComplete();

        // 데이터 변경 - 낮은 부하로 변경
        metricsDataRef.set(createMetricsData(15.0, 20.0, 25.0));

        // 두 번째: 낮은 부하 상태 - 즉시 반영되어야 함
        StepVerifier.create(supplier.get())
            .expectNextMatches(instances -> {
                // 낮은 부하로 인해 높은 가중치
                long instanceCount = instances.stream()
                    .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                    .count();
                assertThat(instanceCount).isGreaterThan(3); // 높은 가중치
                return true;
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Redis 지연 응답 중 데이터 일관성 보장")
    void testDataConsistencyDuringDelayedRedisResponse() {
        // Given - 지연된 응답과 즉시 응답 혼재
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> fastMetrics = createMetricsData(30.0, 35.0, 40.0);
        Map<String, Object> slowMetrics = createMetricsData(25.0, 30.0, 35.0);

        // 빠른 응답
        when(reactiveValueOperations.get(contains("health:service-batch-1")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-1")))
            .thenReturn(Mono.just(fastMetrics));

        // 느린 응답 (2초 지연)
        when(reactiveValueOperations.get(contains("health:service-batch-2")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-2")))
            .thenReturn(Mono.delay(Duration.ofSeconds(2)).then(Mono.just(slowMetrics)));

        // 실패하는 응답
        when(reactiveValueOperations.get(contains("health:service-batch-3")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-3")))
            .thenReturn(Mono.error(new RuntimeException("Connection failed")));

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then - 각 응답 속도와 관계없이 일관된 가중치 적용
        StepVerifier.create(result)
            .expectNextMatches(instances -> {
                assertThat(instances).isNotEmpty();
                
                // 성공적으로 응답한 인스턴스들만 포함
                boolean hasFastInstance = instances.stream()
                    .anyMatch(inst -> "service-batch-1".equals(inst.getInstanceId()));
                boolean hasSlowInstance = instances.stream()
                    .anyMatch(inst -> "service-batch-2".equals(inst.getInstanceId()));
                
                assertThat(hasFastInstance).isTrue();
                assertThat(hasSlowInstance).isTrue();
                
                // 부하점수에 따른 적절한 가중치 분배
                long slowInstanceCount = instances.stream()
                    .filter(inst -> "service-batch-2".equals(inst.getInstanceId()))
                    .count();
                long fastInstanceCount = instances.stream()
                    .filter(inst -> "service-batch-1".equals(inst.getInstanceId()))
                    .count();
                
                // 더 낮은 부하점수(25.0 vs 30.0)의 인스턴스가 더 많이 포함되어야 함
                assertThat(slowInstanceCount).isGreaterThanOrEqualTo(fastInstanceCount);
                
                return true;
            })
            .verifyComplete();
    }

    private void setupStableRedisResponses(Map<String, Object> healthyData, 
                                         Map<String, Object> lowLoadMetrics,
                                         Map<String, Object> midLoadMetrics,
                                         Map<String, Object> highLoadMetrics) {
        // 인스턴스 1: 낮은 부하
        when(reactiveValueOperations.get(contains("health:service-batch-1")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-1")))
            .thenReturn(Mono.just(lowLoadMetrics));

        // 인스턴스 2: 중간 부하
        when(reactiveValueOperations.get(contains("health:service-batch-2")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-2")))
            .thenReturn(Mono.just(midLoadMetrics));

        // 인스턴스 3: 높은 부하
        when(reactiveValueOperations.get(contains("health:service-batch-3")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-3")))
            .thenReturn(Mono.just(highLoadMetrics));
    }

    private void setupOtherInstancesWithHighLoad(Map<String, Object> healthyData) {
        Map<String, Object> highLoadMetrics = createMetricsData(85.0, 90.0, 95.0);
        
        when(reactiveValueOperations.get(contains("health:service-batch-2")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-2")))
            .thenReturn(Mono.just(highLoadMetrics));
        
        when(reactiveValueOperations.get(contains("health:service-batch-3")))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get(contains("metrics:service-batch-3")))
            .thenReturn(Mono.just(highLoadMetrics));
    }
}