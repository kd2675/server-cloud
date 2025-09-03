package com.example.cloud.common.supplier;

import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;

import java.util.Map;

public interface ExtendedServiceInstanceListSupplier extends ServiceInstanceListSupplier {
    Map<String, Object> getDetailedStatus();
}
