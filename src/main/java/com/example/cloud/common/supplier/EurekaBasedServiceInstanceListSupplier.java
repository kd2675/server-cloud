package com.example.cloud.common.supplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    public Map<String, Object> getDetailedStatus() {
        Map<String, Object> status = new HashMap<>();

        try {
            List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);

            // 인스턴스별 기본 정보
            List<Map<String, Object>> instanceDetails = instances.stream()
                    .map(this::createInstanceDetail)
                    .collect(Collectors.toList());

            // 전체 요약 정보
            status.put("serviceId", serviceId);
            status.put("totalInstances", instances.size());
            status.put("discoveryClient", discoveryClient.getClass().getSimpleName());
            status.put("instances", instanceDetails);
            status.put("timestamp", System.currentTimeMillis());

            // Eureka 관련 추가 정보
            status.put("eurekaInfo", getEurekaInfo());

            log.debug("📊 Generated detailed status for {} instances", instances.size());

        } catch (Exception e) {
            log.error("❌ Failed to get detailed status: {}", e.getMessage());
            status.put("error", "Failed to retrieve status: " + e.getMessage());
            status.put("serviceId", serviceId);
            status.put("totalInstances", 0);
            status.put("instances", List.of());
            status.put("timestamp", System.currentTimeMillis());
        }

        return status;
    }

    /**
     * 개별 인스턴스의 상세 정보 생성
     */
    private Map<String, Object> createInstanceDetail(ServiceInstance instance) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("instanceId", instance.getInstanceId());
        detail.put("serviceId", instance.getServiceId());
        detail.put("host", instance.getHost());
        detail.put("port", instance.getPort());
        detail.put("secure", instance.isSecure());
        detail.put("uri", instance.getUri().toString());
        detail.put("metadata", instance.getMetadata());

        // Eureka 특정 정보 (가능한 경우)
        detail.put("scheme", instance.getScheme());

        return detail;
    }

    /**
     * Eureka 관련 추가 정보
     */
    private Map<String, Object> getEurekaInfo() {
        Map<String, Object> eurekaInfo = new HashMap<>();

        try {
            // 전체 서비스 목록
            List<String> services = discoveryClient.getServices();
            eurekaInfo.put("totalServices", services.size());
            eurekaInfo.put("allServices", services);

            // Discovery Client 정보
            eurekaInfo.put("description", discoveryClient.description());
            eurekaInfo.put("order", discoveryClient.getOrder());

        } catch (Exception e) {
            log.warn("⚠️ Failed to get additional Eureka info: {}", e.getMessage());
            eurekaInfo.put("error", "Unable to retrieve Eureka info");
        }

        return eurekaInfo;
    }

}