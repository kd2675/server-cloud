package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.supplier.EurekaWeightedBasedRedisInstanceSupplier;
import com.example.cloud.common.supplier.ExtendedServiceInstanceListSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@DisplayName("Redis 데이터 처리 테스트")
class RedisDataHandlingTest extends WeightedMetricsTestBase {

    @Test
    @DisplayName("부분적인 Redis 데이터 누락 처리")
    void testGetWithPartialRedisData() {
        // Given - 일부 인스턴스만 Redis에 데이터 존재
        Map<String, Object> health1 = createHealthData(true);
        Map<String, Object> metrics1 = createMetricsData(30.0, 40.0, 50.0);

        when(reactiveValueOperations.get(contains("health:service-batch-1")))
                .thenReturn(Mono.just(health1));
        when(reactiveValueOperations.get(contains("metrics:service-batch-1")))
                .thenReturn(Mono.just(metrics1));
        
        // 나머지 인스턴스는 데이터 없음
//        when(reactiveValueOperations.get(contains("health:service-batch-2")))
//                .thenReturn(Mono.empty());
//        when(reactiveValueOperations.get(contains("health:service-batch-3")))
//                .thenReturn(Mono.empty());

        // When
        Flux<List<ServiceInstance>> result = supplier.get();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(instances -> {
                    assertThat(instances).isNotEmpty();
                    
                    // 데이터가 있는 인스턴스가 포함되어야 함
                    boolean hasBatch1 = instances.stream()
                            .anyMatch(inst -> "service-batch-1".equals(inst.getInstanceId()));
                    assertThat(hasBatch1).isTrue();

                    return true;
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("메트릭 데이터의 멀티 조회")
    void testGetAllMetricsFromRedis() {
        // Given
        Object metrics1 = createMetricsData(25.0, 30.0, 40.0);
        Object metrics2 = createMetricsData(45.0, 50.0, 60.0);

        List<Object> responseList = Arrays.asList(metrics1, metrics2, null);
        when(reactiveValueOperations.multiGet(anyList()))
                .thenReturn(Mono.just(responseList));

        // When
        Mono<Map<String, Map<String, Object>>> result = supplier.getAllMetricsFromRedis();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(allMetrics -> {
                    // null 값은 제외되므로 2개만 포함되어야 함
                    assertThat(allMetrics).hasSize(2);
                    assertThat(allMetrics).containsKeys(
                            "service-batch-1", 
                            "service-batch-2"
                    );
                    
                    // 메트릭 데이터 검증
                    Map<String, Object> batch1Metrics = allMetrics.get("service-batch-1");
                    assertThat(batch1Metrics.get("loadScore")).isEqualTo(25.0);
                    assertThat(batch1Metrics.get("cpuUsage")).isEqualTo(30.0);

                    return true;
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("빈 메트릭 데이터 처리")
    void testGetAllMetricsFromRedisWithEmptyData() {
        // Given - 빈 리스트 반환
        when(reactiveValueOperations.multiGet(anyList()))
                .thenReturn(Mono.just(Collections.emptyList()));

        // When
        Mono<Map<String, Map<String, Object>>> result = supplier.getAllMetricsFromRedis();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(allMetrics -> {
                    assertThat(allMetrics).isEmpty();
                    return true;
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("부분적으로만 데이터가 있는 경우")
    void testGetAllMetricsFromRedisWithPartialData() {
        // Given - 일부만 데이터 있음
        Object metrics1 = createMetricsData(25.0, 30.0, 40.0);
        
        List<Object> responseList = Arrays.asList(metrics1, null, null);
        when(reactiveValueOperations.multiGet(anyList()))
                .thenReturn(Mono.just(responseList));

        // When
        Mono<Map<String, Map<String, Object>>> result = supplier.getAllMetricsFromRedis();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(allMetrics -> {
                    assertThat(allMetrics).hasSize(1);
                    assertThat(allMetrics).containsKey("service-batch-1");
                    
                    Map<String, Object> batch1Metrics = allMetrics.get("service-batch-1");
                    assertThat(batch1Metrics.get("loadScore")).isEqualTo(25.0);
                    
                    return true;
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Redis multiGet 실패 시 처리")
    void testGetAllMetricsFromRedisWithError() {
        // Given - Redis multiGet 오류
        when(reactiveValueOperations.multiGet(anyList()))
                .thenReturn(Mono.error(new RuntimeException("Redis multiGet failed")));

        // When
        Mono<Map<String, Map<String, Object>>> result = supplier.getAllMetricsFromRedis();

        // Then
        StepVerifier.create(result)
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    @DisplayName("Redis Template이 null인 경우의 getAllMetricsFromRedis")
    void testGetAllMetricsFromRedisWithNullRedisTemplate() {
        // Given - Redis Template이 null인 Supplier
        ExtendedServiceInstanceListSupplier supplierWithNullRedis =
                new EurekaWeightedBasedRedisInstanceSupplier(context, discoveryClient, null);

        // When
        Mono<Map<String, Map<String, Object>>> result = supplierWithNullRedis.getAllMetricsFromRedis();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(allMetrics -> {
                    assertThat(allMetrics).isEmpty();
                    return true;
                })
                .verifyComplete();
    }
}