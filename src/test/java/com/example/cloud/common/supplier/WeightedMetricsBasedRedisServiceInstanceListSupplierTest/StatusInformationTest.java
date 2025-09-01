package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest.WeightedMetricsTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@DisplayName("상태 정보 조회 테스트")
class StatusInformationTest extends WeightedMetricsTestBase {

    @Test
    @DisplayName("상세 상태 정보 조회")
    void testGetDetailedStatusFromRedis() {
        // Given
        Object metrics1 = createMetricsData(30.0, 25.0, 40.0);
        Object metrics2 = createMetricsData(60.0, 70.0, 65.0);

        List<Object> responseList = Arrays.asList(metrics1, metrics2, null);
        when(reactiveValueOperations.multiGet(anyList()))
                .thenReturn(Mono.just(responseList));

        // When
        Mono<Map<String, Object>> result = supplier.getDetailedStatusFromRedis();

        // Then
        StepVerifier.create(result)
                .expectNextMatches(status -> {
                    assertThat(status).containsKeys(
                            "serviceId", "strategy", "totalInstances", 
                            "instances", "redisEnabled", "weightRange"
                    );
                    
                    assertThat(status.get("serviceId")).isEqualTo("service-batch");
                    assertThat(status.get("strategy")).isEqualTo("WEIGHTED");
                    assertThat(status.get("totalInstances")).isEqualTo(3);
                    assertThat(status.get("redisEnabled")).isEqualTo(true);

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> instances = 
                            (List<Map<String, Object>>) status.get("instances");
                    assertThat(instances).hasSize(3);

                    // 가중치 정보 검증
                    @SuppressWarnings("unchecked")
                    Map<String, Object> weightRange = (Map<String, Object>) status.get("weightRange");
                    assertThat(weightRange).containsKeys("min", "max");
                    
                    // Double 타입으로 비교
                    Object minWeight = weightRange.get("min");
                    Object maxWeight = weightRange.get("max");
                    
                    if (minWeight instanceof Number) {
                        assertThat(((Number) minWeight).doubleValue()).isEqualTo(1.0);
                    }
                    if (maxWeight instanceof Number) {
                        assertThat(((Number) maxWeight).doubleValue()).isEqualTo(10.0);
                    }

                    return true;
                })
                .verifyComplete();
    }
}