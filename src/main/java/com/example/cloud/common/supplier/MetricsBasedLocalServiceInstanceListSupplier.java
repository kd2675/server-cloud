package com.example.cloud.common.supplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MetricsBasedLocalServiceInstanceListSupplier implements ServiceInstanceListSupplier {
    private final String serviceId = "service-batch";
    private final WebClient webClient;
    private final List<LoadBalancedServiceBatchInstance> staticInstances;

    // 메트릭 캐시 (30초 TTL)
    private final Map<String, Map<String, Object>> metricsCache = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private final long CACHE_TTL = 30000; // 30초

    public MetricsBasedLocalServiceInstanceListSupplier(ConfigurableApplicationContext context) {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();

        // application-local.yml에서 포트 정보 읽기
        String serverHost = context.getEnvironment().getProperty("path.service.batch.host");
        int serverPort1 = context.getEnvironment().getProperty("path.service.batch.port1", Integer.class);
        int serverPort2 = context.getEnvironment().getProperty("path.service.batch.port2", Integer.class);
        int serverPort3 = context.getEnvironment().getProperty("path.service.batch.port3", Integer.class);

        // 정적 인스턴스 정의
        this.staticInstances = Arrays.asList(
                new LoadBalancedServiceBatchInstance("service-batch-1", serverHost, serverPort1),
                new LoadBalancedServiceBatchInstance("service-batch-2", serverHost, serverPort2),
                new LoadBalancedServiceBatchInstance("service-batch-3", serverHost, serverPort3)
        );

        log.info("MetricsBasedLoadBalancer 초기화 완료 - {}:{}|{}:{}|{}:{}",
                serverHost, serverPort1, serverHost, serverPort2, serverHost, serverPort3);

        // 백그라운드 모니터링 시작
        startMetricsAndHealthMonitoring();
    }

    @Override
    public String getServiceId() {
        return serviceId;
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        // 건강한 인스턴스를 부하 점수 순으로 정렬하여 반환
        List<ServiceInstance> sortedInstances = staticInstances.stream()
                .filter(instance -> instance.isHealthy.get())
                .sorted(Comparator.comparingDouble(this::getInstanceLoadScore))
                .collect(Collectors.toList());

        if (sortedInstances.isEmpty()) {
            log.warn("건강한 service-batch 인스턴스가 없습니다. 모든 인스턴스를 반환합니다.");
            return Flux.just(new ArrayList<>(staticInstances));
        }

        // 부하 점수 로깅
        if (log.isDebugEnabled()) {
            sortedInstances.forEach(instance -> {
                double loadScore = getInstanceLoadScore((LoadBalancedServiceBatchInstance) instance);
                log.info("인스턴스 {} 부하점수: {}", instance.getInstanceId(), String.format("%.2f", loadScore));
            });
        }

        log.info("부하 기준 정렬된 활성 인스턴스 수: {}/{}",
                sortedInstances.size(), staticInstances.size());

        return Flux.just(sortedInstances);
    }

    private void startMetricsAndHealthMonitoring() {
        // 15초마다 헬스체크 및 메트릭 수집
        Flux.interval(Duration.ofSeconds(15))
                .doOnNext(tick -> {
                    log.info("메트릭 및 헬스 모니터링 시작 ({})", tick);
                    performHealthAndMetricsCheck();
                })
                .subscribe();
    }

    private void performHealthAndMetricsCheck() {
        staticInstances.parallelStream().forEach(instance -> {
            try {
                // 1. Actuator 헬스체크
                checkActuatorHealth(instance);

                // 2. 메트릭 수집 (건강한 경우에만)
                if (instance.isHealthy.get()) {
                    collectLoadMetrics(instance);
                }
            } catch (Exception e) {
                log.info("인스턴스 {} 모니터링 실패: {}", instance.getInstanceId(), e.getMessage());
            }
        });
    }

    /**
     * Actuator health 엔드포인트로 헬스체크
     */
    private void checkActuatorHealth(LoadBalancedServiceBatchInstance instance) {
        String healthUrl = instance.getUri() + "/actuator/health";

        webClient.get()
                .uri(healthUrl)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(5))
                .subscribe(
                        response -> {
                            boolean wasHealthy = instance.isHealthy.get();
                            boolean isHealthy = response.getStatusCode().is2xxSuccessful();
                            instance.isHealthy.set(isHealthy);

                            if (wasHealthy != isHealthy) {
                                log.info("인스턴스 {}:{} 헬스 상태 변경: {} -> {}",
                                        instance.getHost(), instance.getPort(),
                                        wasHealthy ? "UP" : "DOWN",
                                        isHealthy ? "UP" : "DOWN");
                            }
                        },
                        error -> {
                            if (instance.isHealthy.getAndSet(false)) {
                                log.warn("인스턴스 {}:{} 헬스체크 실패: {}",
                                        instance.getHost(), instance.getPort(),
                                        error.getMessage());
                            }
                        }
                );
    }

    /**
     * ActuatorMetricsController의 /service/batch/metrics/load 엔드포인트에서 메트릭 수집
     */
    private void collectLoadMetrics(LoadBalancedServiceBatchInstance instance) {
        String metricsUrl = instance.getUri() + "/service/batch/metrics/load";

        webClient.get()
                .uri(metricsUrl)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(8))
                .subscribe(
                        metrics -> {
                            String instanceId = instance.getInstanceId();
                            metricsCache.put(instanceId, metrics);
                            cacheTimestamps.put(instanceId, System.currentTimeMillis());

                            Object loadScore = metrics.get("loadScore");
                            Object isHealthy = metrics.get("isHealthy");
                            Object cpuUsage = metrics.get("cpuUsage");
                            Object memoryUsage = metrics.get("memoryUsage");

                            log.info("인스턴스 {} 메트릭 수집 성공 - 부하점수: {}, CPU: {}%, 메모리: {}%, 건강상태: {}",
                                    instanceId,
                                    loadScore instanceof Double ? String.format("%.2f", (Double) loadScore) : "0.00",
                                    cpuUsage instanceof Double ? String.format("%.1f", (Double) cpuUsage) : "0.0",
                                    memoryUsage instanceof Double ? String.format("%.1f", (Double) memoryUsage) : "0.0",
                                    isHealthy);
                        },
                        error -> {
                            log.info("인스턴스 {}:{} 메트릭 수집 실패: {}",
                                    instance.getHost(), instance.getPort(),
                                    error.getMessage());

                            // 메트릭 수집 실패 시 캐시에서 제거
                            metricsCache.remove(instance.getInstanceId());
                            cacheTimestamps.remove(instance.getInstanceId());
                        }
                );
    }

    /**
     * 인스턴스의 부하 점수 조회 (낮을수록 좋음)
     */
    private double getInstanceLoadScore(LoadBalancedServiceBatchInstance instance) {
        String instanceId = instance.getInstanceId();

        // 캐시 확인
        Long cacheTime = cacheTimestamps.get(instanceId);
        if (cacheTime != null && (System.currentTimeMillis() - cacheTime) < CACHE_TTL) {
            Map<String, Object> cachedMetrics = metricsCache.get(instanceId);
            if (cachedMetrics != null) {
                Object loadScore = cachedMetrics.get("loadScore");
                if (loadScore instanceof Double) {
                    return (Double) loadScore;
                }
            }
        }

        return 100.0; // 캐시에 없으면 최대 부하로 처리 (우선순위 낮음)
    }

    /**
     * 모든 인스턴스의 메트릭 정보 조회
     */
    public Map<String, Map<String, Object>> getAllMetrics() {
        return new HashMap<>(metricsCache);
    }

    /**
     * 로드밸런서 상태 요약 (메트릭 정보 포함)
     */
    public Map<String, Object> getDetailedStatus() {
        Map<String, Object> status = new HashMap<>();

        List<Map<String, Object>> instances = staticInstances.stream()
                .map(instance -> {
                    Map<String, Object> instanceInfo = new HashMap<>();
                    instanceInfo.put("instanceId", instance.getInstanceId());
                    instanceInfo.put("host", instance.getHost());
                    instanceInfo.put("port", instance.getPort());
                    instanceInfo.put("isHealthy", instance.isHealthy.get());
                    instanceInfo.put("uri", instance.getUri().toString());

                    // 메트릭 정보 추가
                    Map<String, Object> metrics = metricsCache.get(instance.getInstanceId());
                    if (metrics != null) {
                        instanceInfo.put("loadScore", metrics.get("loadScore"));
                        instanceInfo.put("cpuUsage", metrics.get("cpuUsage"));
                        instanceInfo.put("memoryUsage", metrics.get("memoryUsage"));
                        instanceInfo.put("activeThreads", metrics.get("activeThreads"));
                        instanceInfo.put("responseTime", metrics.get("responseTime"));
                        instanceInfo.put("requestCount", metrics.get("requestCount"));
                        instanceInfo.put("lastUpdated", cacheTimestamps.get(instance.getInstanceId()));
                    } else {
                        instanceInfo.put("loadScore", 100.0);
                        instanceInfo.put("metricsStatus", "NO_DATA");
                    }

                    return instanceInfo;
                })
                .collect(Collectors.toList());

        // 전체 상태 요약
        long healthyCount = staticInstances.stream()
                .mapToLong(instance -> instance.isHealthy.get() ? 1 : 0)
                .sum();

        long metricsAvailableCount = metricsCache.size();

        status.put("serviceId", serviceId);
        status.put("totalInstances", staticInstances.size());
        status.put("healthyInstances", healthyCount);
        status.put("metricsAvailableInstances", metricsAvailableCount);
        status.put("instances", instances);
        status.put("timestamp", System.currentTimeMillis());

        // 최적 인스턴스 정보
        Optional<LoadBalancedServiceBatchInstance> bestInstance = staticInstances.stream()
                .filter(instance -> instance.isHealthy.get())
                .min(Comparator.comparingDouble(this::getInstanceLoadScore));

        if (bestInstance.isPresent()) {
            Map<String, Object> bestInfo = new HashMap<>();
            bestInfo.put("instanceId", bestInstance.get().getInstanceId());
            bestInfo.put("loadScore", getInstanceLoadScore(bestInstance.get()));
            status.put("currentBestInstance", bestInfo);
        }

        return status;
    }

    /**
     * LoadBalancer 지원 ServiceInstance 구현체
     */
    private static class LoadBalancedServiceBatchInstance implements ServiceInstance {
        private final String instanceId;
        private final String host;
        private final int port;
        private final AtomicBoolean isHealthy = new AtomicBoolean(true);

        public LoadBalancedServiceBatchInstance(String instanceId, String host, int port) {
            this.instanceId = instanceId;
            this.host = host;
            this.port = port;
        }

        @Override
        public String getServiceId() {
            return "service-batch";
        }

        @Override
        public String getInstanceId() {
            return instanceId;
        }

        @Override
        public String getHost() {
            return host;
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public URI getUri() {
            return URI.create("http://" + host + ":" + port);
        }

        @Override
        public Map<String, String> getMetadata() {
            return Map.of(
                    "zone", "default",
                    "healthy", String.valueOf(isHealthy.get()),
                    "loadBalanced", "true",
                    "metricsEnabled", "true"
            );
        }
    }
}