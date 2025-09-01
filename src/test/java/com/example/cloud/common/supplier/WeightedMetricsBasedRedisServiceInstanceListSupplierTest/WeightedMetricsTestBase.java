package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
public abstract class WeightedMetricsTestBase {

    @Mock
    protected ConfigurableApplicationContext context;

    @Mock
    protected ConfigurableEnvironment environment;

    @Mock
    protected ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;

    @Mock
    protected ReactiveValueOperations<String, Object> reactiveValueOperations;

    protected WeightedMetricsBasedRedisServiceInstanceListSupplier supplier;

    @BeforeEach
    void setUpBase() {
        setupBasicMocks();
        supplier = new WeightedMetricsBasedRedisServiceInstanceListSupplier(context, reactiveRedisTemplate);
    }

    private void setupBasicMocks() {
        lenient().when(context.getEnvironment()).thenReturn(environment);
        lenient().when(environment.getProperty("path.service.batch.host")).thenReturn("localhost");
        lenient().when(environment.getProperty("path.service.batch.port1", Integer.class)).thenReturn(8081);
        lenient().when(environment.getProperty("path.service.batch.port2", Integer.class)).thenReturn(8082);
        lenient().when(environment.getProperty("path.service.batch.port3", Integer.class)).thenReturn(8083);
        lenient().when(reactiveRedisTemplate.opsForValue()).thenReturn(reactiveValueOperations);
    }

    // 헬퍼 메서드들
    protected Map<String, Object> createHealthData(boolean isHealthy) {
        Map<String, Object> healthData = new HashMap<>();
        healthData.put("isHealthy", isHealthy);
        healthData.put("timestamp", System.currentTimeMillis());
        return healthData;
    }

    protected Map<String, Object> createMetricsData(double loadScore, double cpuUsage, double memoryUsage) {
        Map<String, Object> metricsData = new HashMap<>();
        metricsData.put("loadScore", loadScore);
        metricsData.put("cpuUsage", cpuUsage);
        metricsData.put("memoryUsage", memoryUsage);
        metricsData.put("requestCount", 100L);
        metricsData.put("responseTime", 150.0);
        metricsData.put("timestamp", System.currentTimeMillis());
        return metricsData;
    }
}