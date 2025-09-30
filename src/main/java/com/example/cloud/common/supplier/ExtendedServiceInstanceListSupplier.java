package com.example.cloud.common.supplier;

import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface ExtendedServiceInstanceListSupplier extends ServiceInstanceListSupplier {
    Map<String, Object> getDetailedStatus();
    Mono<Map<String, Object>> getDetailedStatusFromRedis();
    Mono<Map<String, Map<String, Object>>> getAllMetricsFromRedis();
    Map<String, Map<String, Object>> getAllMetrics();
}
