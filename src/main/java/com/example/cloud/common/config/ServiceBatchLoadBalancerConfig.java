package com.example.cloud.common.config;

import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.context.annotation.Configuration;

@Configuration
@LoadBalancerClient(name = "service-batch", configuration = ServiceBatchLoadBalancerConfig.class)
public class ServiceBatchLoadBalancerConfig {

    // 🚀 Eureka 사용 (기본값)
//    @Bean
//    @ConditionalOnProperty(name = "loadbalancer.strategy", havingValue = "eureka", matchIfMissing = true)
//    public ServiceInstanceListSupplier eurekaBasedServiceInstanceListSupplier(
//            ConfigurableApplicationContext context,
//            DiscoveryClient discoveryClient,
//            ReactiveRedisTemplate<String, Object> reactiveRedisTemplate) {
//
//        return new EurekaBasedServiceInstanceListSupplier(discoveryClient, reactiveRedisTemplate);
//    }
//
//    // 🔧 기존 수동 방식 (설정으로 선택 가능)
//    @Bean
//    @ConditionalOnProperty(name = "loadbalancer.strategy", havingValue = "manual")
//    public ServiceInstanceListSupplier weightedMetricsBasedServiceInstanceListSupplier(
//            ConfigurableApplicationContext context,
//            ReactiveRedisTemplate<String, Object> reactiveRedisTemplate) {
//
//        return new WeightedMetricsBasedRedisServiceInstanceListSupplier(context, reactiveRedisTemplate);
//    }

}
