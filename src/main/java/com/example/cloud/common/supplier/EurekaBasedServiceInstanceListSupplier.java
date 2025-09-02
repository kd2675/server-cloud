package com.example.cloud.common.supplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

@Slf4j
public class EurekaBasedServiceInstanceListSupplier implements ServiceInstanceListSupplier {
    
    private final String serviceId = "service-batch";
    private final DiscoveryClient discoveryClient;
    private final ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;

    public EurekaBasedServiceInstanceListSupplier(
            DiscoveryClient discoveryClient,
            ReactiveRedisTemplate<String, Object> reactiveRedisTemplate) {
        this.discoveryClient = discoveryClient;
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        
        log.info("🚀 Eureka 기반 로드밸런서 초기화 완료");
    }

    @Override
    public String getServiceId() {
        return serviceId;
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return Flux.interval(Duration.ofSeconds(10))  // 🔄 10초마다 갱신
                .startWith(0L)
                .map(tick -> getInstancesFromEureka())
                .filter(instances -> !instances.isEmpty())
                .doOnNext(instances -> 
                    log.info("🎯 Eureka에서 발견된 {} 인스턴스: {}", 
                        serviceId, instances.size()))
                .onErrorResume(error -> {
                    log.error("Eureka 조회 실패, fallback 사용: {}", error.getMessage());
                    return Flux.just(List.of()); // 빈 리스트 반환
                });
    }

    private List<ServiceInstance> getInstancesFromEureka() {
        try {
            List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);
            log.debug("Eureka에서 조회된 인스턴스들: {}", 
                instances.stream()
                    .map(instance -> instance.getInstanceId())
                    .toList());
            return instances;
        } catch (Exception e) {
            log.error("Eureka 인스턴스 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }
}