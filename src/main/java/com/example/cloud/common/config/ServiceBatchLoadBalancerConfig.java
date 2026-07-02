package com.example.cloud.common.config;

import com.example.cloud.common.supplier.EurekaWeightedBasedRedisInstanceSupplier;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@LoadBalancerClient(name = "service-batch", configuration = ServiceBatchLoadBalancerClientConfig.class)
public class ServiceBatchLoadBalancerConfig {
}

class ServiceBatchLoadBalancerClientConfig {
    @Bean
    ServiceInstanceListSupplier serviceInstanceListSupplier(EurekaWeightedBasedRedisInstanceSupplier supplier) {
        return supplier;
    }
}
