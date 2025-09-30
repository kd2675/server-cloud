package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.supplier.EurekaWeightedBasedRedisInstanceSupplier;
import com.example.cloud.common.supplier.ExtendedServiceInstanceListSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("기본 기능 테스트")
class BasicFunctionalityTest extends WeightedMetricsTestBase {

    @Test
    @DisplayName("서비스 ID 반환 테스트")
    void testGetServiceId() {
        // When
        String serviceId = supplier.getServiceId();

        // Then
        assertThat(serviceId).isEqualTo("service-batch");
    }

    @Test
    @DisplayName("Redis 없이 동작하는 경우")
    void testOperationWithoutRedis() {
        // Given - Redis 없이 생성
        ExtendedServiceInstanceListSupplier supplierWithoutRedis =
                new EurekaWeightedBasedRedisInstanceSupplier(context, discoveryClient, null);

        // When
        Map<String, Map<String, Object>> metrics = supplierWithoutRedis.getAllMetrics();
        Map<String, Object> status = supplierWithoutRedis.getDetailedStatus();

        // Then
        assertThat(metrics).isEmpty();
        assertThat(status).containsKey("redisEnabled");
        assertThat(status.get("redisEnabled")).isEqualTo(false);
    }
}