package com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplierTest;

import com.example.cloud.common.supplier.EurekaWeightedBasedRedisInstanceSupplier;
import com.example.cloud.common.supplier.ExtendedServiceInstanceListSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;

import java.util.HashMap;
import java.util.List;
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
    protected DiscoveryClient discoveryClient;

    @Mock
    protected ReactiveValueOperations<String, Object> reactiveValueOperations;

    protected ExtendedServiceInstanceListSupplier supplier;

    @BeforeEach
    void setUpBase() {
        setupBasicMocks();
//        supplier = new WeightedMetricsBasedRedisServiceInstanceListSupplier(context, reactiveRedisTemplate);
        supplier = new EurekaWeightedBasedRedisInstanceSupplier(context, discoveryClient, reactiveRedisTemplate);
    }

    private void setupBasicMocks() {
//        lenient().when(context.getEnvironment()).thenReturn(environment);
//        lenient().when(environment.getProperty("path.service.batch.host")).thenReturn("localhost");
//        lenient().when(environment.getProperty("path.service.batch.port1", Integer.class)).thenReturn(8081);
//        lenient().when(environment.getProperty("path.service.batch.port2", Integer.class)).thenReturn(8082);
        lenient().when(environment.getProperty("path.service.batch.port3", Integer.class)).thenReturn(8083);
//        lenient().when(reactiveRedisTemplate.opsForValue()).thenReturn(reactiveValueOperations);
        lenient().when(context.getEnvironment()).thenReturn(environment);
        // Redis ValueOps 목킹
        lenient().when(reactiveRedisTemplate.opsForValue()).thenReturn(reactiveValueOperations);

        // Eureka에서 반환될 인스턴스 3개 구성
        List<ServiceInstance> discovered = List.of(
                createInstance("service-batch-1", "localhost", 8081),
                createInstance("service-batch-2", "localhost", 8082),
                createInstance("service-batch-3", "localhost", 8083)
        );
        lenient().when(discoveryClient.getInstances("service-batch")).thenReturn(discovered);

    }

    private ServiceInstance createInstance(String instanceId, String host, int port) {
        return new ServiceInstance() {
            @Override public String getServiceId() { return "service-batch"; }
            @Override public String getInstanceId() { return instanceId; }
            @Override public String getHost() { return host; }
            @Override public int getPort() { return port; }
            @Override public boolean isSecure() { return false; }
            @Override public java.net.URI getUri() { return java.net.URI.create("http://" + host + ":" + port); }
            @Override public Map<String, String> getMetadata() { return Map.of(); }
            @Override public String getScheme() { return "http"; }
        };
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