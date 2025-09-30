package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

@DisplayName("통계적 가중치 분배 검증 테스트")
class StatisticalWeightDistributionTest extends WeightedMetricsTestBase {

    @Test
    @DisplayName("1:5:10 가중치 비율 통계적 검증 (1000회 샘플링)")
    void testWeightDistributionRatio1_5_10WithStatisticalValidation() {
        // Given - 정확한 Redis 키로 Mock 설정
        Map<String, Object> healthyData = createHealthData(true);
        
        // 가중치 1:5:10 생성 (부하점수 100:20:10)
        Map<String, Object> highLoadMetrics = createMetricsData(100.0, 95.0, 90.0); // weight 1
        Map<String, Object> midLoadMetrics = createMetricsData(20.0, 25.0, 30.0);   // weight 5
        Map<String, Object> lowLoadMetrics = createMetricsData(10.0, 15.0, 20.0);   // weight 10

        setupRedisForWeightTesting(healthyData, highLoadMetrics, midLoadMetrics, lowLoadMetrics);

        // When - 1000회 샘플링
        int sampleCount = 1000;
        Map<String, AtomicInteger> instanceCounts = new HashMap<>();
        instanceCounts.put("service-batch-1", new AtomicInteger(0));
        instanceCounts.put("service-batch-2", new AtomicInteger(0));
        instanceCounts.put("service-batch-3", new AtomicInteger(0));

        for (int i = 0; i < sampleCount; i++) {
            StepVerifier.create(supplier.get())
                .expectNextMatches(instances -> {
                    instances.forEach(instance -> {
                        instanceCounts.get(instance.getInstanceId()).incrementAndGet();
                    });
                    return true;
                })
                .verifyComplete();
        }

        // Then - 통계적 검증
        int totalInstances = instanceCounts.values().stream()
            .mapToInt(AtomicInteger::get)
            .sum();

        double batch1Ratio = (double) instanceCounts.get("service-batch-1").get() / totalInstances;
        double batch2Ratio = (double) instanceCounts.get("service-batch-2").get() / totalInstances;
        double batch3Ratio = (double) instanceCounts.get("service-batch-3").get() / totalInstances;

        // 예상 비율: 1:5:10 = 1/16, 5/16, 10/16
        double expectedBatch1Ratio = 1.0 / 16.0;
        double expectedBatch2Ratio = 5.0 / 16.0;
        double expectedBatch3Ratio = 10.0 / 16.0;

        double tolerance = 0.03; // ±3%

        assertThat(batch1Ratio).isCloseTo(expectedBatch1Ratio, within(tolerance));
        assertThat(batch2Ratio).isCloseTo(expectedBatch2Ratio, within(tolerance));
        assertThat(batch3Ratio).isCloseTo(expectedBatch3Ratio, within(tolerance));
    }

    @Test
    @DisplayName("극단적 가중치 차이에서의 분배 정확성 (1:1:10)")
    void testExtremeWeightDifference() {
        // Given - 정확한 Redis 키로 Mock 설정
        Map<String, Object> healthyData = createHealthData(true);
        
        Map<String, Object> veryHighLoadMetrics = createMetricsData(100.0, 95.0, 90.0); // weight 1
        Map<String, Object> highLoadMetrics = createMetricsData(100.0, 95.0, 90.0);     // weight 1
        Map<String, Object> lowLoadMetrics = createMetricsData(10.0, 15.0, 20.0);       // weight 10

        when(reactiveValueOperations.get("loadbalancer:health:service-batch-1"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-1"))
            .thenReturn(Mono.just(veryHighLoadMetrics));
        
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-2"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-2"))
            .thenReturn(Mono.just(highLoadMetrics));
        
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-3"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-3"))
            .thenReturn(Mono.just(lowLoadMetrics));

        // When - 500회 테스트
        int sampleCount = 500;
        Map<String, AtomicInteger> counts = new HashMap<>();
        counts.put("service-batch-1", new AtomicInteger(0));
        counts.put("service-batch-2", new AtomicInteger(0));
        counts.put("service-batch-3", new AtomicInteger(0));

        for (int i = 0; i < sampleCount; i++) {
            StepVerifier.create(supplier.get())
                .expectNextMatches(instances -> {
                    instances.forEach(instance -> 
                        counts.get(instance.getInstanceId()).incrementAndGet());
                    return true;
                })
                .verifyComplete();
        }

        // Then - batch-3가 압도적으로 많아야 함
        int batch3Count = counts.get("service-batch-3").get();
        int batch1Count = counts.get("service-batch-1").get();
        int batch2Count = counts.get("service-batch-2").get();
        int totalCount = batch1Count + batch2Count + batch3Count;

        double batch3Ratio = (double) batch3Count / totalCount;
        
        // batch-3가 80% 이상
        assertThat(batch3Ratio).isGreaterThan(0.8);
        
        // batch-1과 batch-2는 비슷한 비율
        double difference = Math.abs((double) batch1Count / totalCount - (double) batch2Count / totalCount);
        assertThat(difference).isLessThan(0.05);
    }

    @Test
    @DisplayName("동일한 가중치에서의 균등 분배 확인")
    void testEqualWeightDistribution() {
        // Given - 정확한 Redis 키로 Mock 설정
        Map<String, Object> healthyData = createHealthData(true);
        Map<String, Object> equalLoadMetrics = createMetricsData(50.0, 55.0, 60.0);

        when(reactiveValueOperations.get("loadbalancer:health:service-batch-1"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-1"))
            .thenReturn(Mono.just(equalLoadMetrics));
        
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-2"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-2"))
            .thenReturn(Mono.just(equalLoadMetrics));
        
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-3"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-3"))
            .thenReturn(Mono.just(equalLoadMetrics));

        // When - 300회 테스트
        int sampleCount = 300;
        Map<String, AtomicInteger> counts = new HashMap<>();
        counts.put("service-batch-1", new AtomicInteger(0));
        counts.put("service-batch-2", new AtomicInteger(0));
        counts.put("service-batch-3", new AtomicInteger(0));

        for (int i = 0; i < sampleCount; i++) {
            StepVerifier.create(supplier.get())
                .expectNextMatches(instances -> {
                    instances.forEach(instance -> 
                        counts.get(instance.getInstanceId()).incrementAndGet());
                    return true;
                })
                .verifyComplete();
        }

        // Then - 균등 분배 (각 33.33% ± 5%)
        int totalCount = counts.values().stream().mapToInt(AtomicInteger::get).sum();
        
        counts.forEach((instanceId, count) -> {
            double ratio = (double) count.get() / totalCount;
            assertThat(ratio)
                .describedAs("Instance %s should have ~33.33%% distribution", instanceId)
                .isCloseTo(1.0 / 3.0, within(0.05));
        });
    }

    private void setupRedisForWeightTesting(Map<String, Object> healthyData,
                                          Map<String, Object> highLoadMetrics,
                                          Map<String, Object> midLoadMetrics,
                                          Map<String, Object> lowLoadMetrics) {
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-1"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-1"))
            .thenReturn(Mono.just(highLoadMetrics));
        
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-2"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-2"))
            .thenReturn(Mono.just(midLoadMetrics));
        
        when(reactiveValueOperations.get("loadbalancer:health:service-batch-3"))
            .thenReturn(Mono.just(healthyData));
        when(reactiveValueOperations.get("loadbalancer:metrics:service-batch-3"))
            .thenReturn(Mono.just(lowLoadMetrics));
    }
}