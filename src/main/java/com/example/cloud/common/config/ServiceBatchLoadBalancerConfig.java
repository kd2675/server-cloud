package com.example.cloud.common.config;

import com.example.cloud.common.supplier.DynamicServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@LoadBalancerClient(name = "service-batch", configuration = ServiceBatchLoadBalancerConfig.class)
public class ServiceBatchLoadBalancerConfig {

    @Bean
    @Primary
    public ServiceInstanceListSupplier serviceBatchInstanceSupplier(ConfigurableApplicationContext context) {
//        return new ServiceBatchInstanceListSupplier(context);
        return new DynamicServiceInstanceListSupplier(context);
    }
}
