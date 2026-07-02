package com.example.cloud.common.supplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
public class MetricsBasedRedisServiceInstanceListSupplier implements ServiceInstanceListSupplier {
    private final String serviceId = "service-batch";
    private final WebClient webClient;
    private final List<LoadBalancedServiceBatchInstance> staticInstances;

    // Redis 캐시 사용
    private final ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;
    private final long CACHE_TTL_SECONDS = 30; // 30초 TTL

    // Redis 키 패턴
    private static final String METRICS_KEY_PREFIX = "loadbalancer:metrics:";
    private static final String HEALTH_KEY_PREFIX = "loadbalancer:health:";

    public MetricsBasedRedisServiceInstanceListSupplier(
            ConfigurableApplicationContext context,
            @Autowired(required = false) ReactiveRedisTemplate<String, Object> reactiveRedisTemplate) {

        this.reactiveRedisTemplate = reactiveRedisTemplate;
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

        log.info("MetricsBasedLoadBalancer 초기화 완료 (Redis 캐시 활성화: {}) - {}:{}|{}:{}|{}:{}",
                reactiveRedisTemplate != null,
                serverHost, serverPort1, serverHost, serverPort2, serverHost, serverPort3);

        // 백그라운드 모니터링 시작
        startMetricsAndHealthMonitoring();
    }

    @Override
    public String getServiceId() {
        return serviceId;
    }

    /**
     * 🚀 완전 비동기 버전으로 변경
     */
    @Override
    public Flux<List<ServiceInstance>> get() {
        return getHealthyInstancesFromRedis()
                .flatMap(this::sortInstancesByLoadScoreAsync)  // 🔥 비동기로 변경
                .onErrorResume(error -> {
                    log.warn("Redis에서 인스턴스 조회 실패, fallback 사용: {}", error.getMessage());
                    return Mono.just(getFallbackInstances());
                })
                .flux();
    }

    /**
     * 🔥 비동기로 부하점수 기반 정렬 (Map.Entry 방식 적용)
     */
    private Mono<List<ServiceInstance>> sortInstancesByLoadScoreAsync(List<LoadBalancedServiceBatchInstance> healthyInstances) {
        if (healthyInstances.isEmpty()) {
            log.warn("건강한 service-batch 인스턴스가 없습니다. fallback 인스턴스를 반환합니다.");
            return Mono.just(getFallbackInstances());
        }

        return Flux.fromIterable(healthyInstances)
                .flatMap(instance -> 
                    getInstanceLoadScoreAsync(instance)
                        .map(score -> Map.entry(instance, score))  // ✅ Map.Entry 사용
                )
                .collectList()
                .map(entries -> {
                    // 부하점수 기준 정렬 후 인스턴스만 추출
                    List<ServiceInstance> sortedInstances = entries.stream()
                            .sorted(Map.Entry.comparingByValue())  // ✅ Map.Entry 정렬
                            .map(Map.Entry::getKey)               // ✅ 인스턴스만 추출
                            .collect(Collectors.toList());

                    // 정렬 결과 로그
                    String sortOrder = entries.stream()
                            .sorted(Map.Entry.comparingByValue())
                            .map(entry -> String.format("%s(%.1f)", 
                                entry.getKey().getInstanceId(), 
                                entry.getValue()))
                            .collect(Collectors.joining(" → "));
                    
                    log.info("부하점수 기준 정렬 완료: {}", sortOrder);
                    log.info("활성 인스턴스 수: {}/{}", sortedInstances.size(), staticInstances.size());
                    
                    return sortedInstances;
                })
                .doOnError(error -> log.error("부하점수 기반 정렬 실패", error))
                .onErrorReturn(getFallbackInstances());
}

    /**
     * 🔥 비동기로 부하점수 조회
     */
    private Mono<Double> getInstanceLoadScoreAsync(ServiceInstance instance) {
        if (reactiveRedisTemplate == null) {
            return Mono.just(100.0);
        }

        String key = METRICS_KEY_PREFIX + instance.getInstanceId();

        return reactiveRedisTemplate.opsForValue()
                .get(key)
                .cast(Map.class)
                .timeout(Duration.ofSeconds(3))
                .map(metrics -> {
                    Object loadScore = metrics.get("loadScore");
                    if (loadScore instanceof Number) {
                        double score = ((Number) loadScore).doubleValue();
                        return score;
                    }
                    log.error("loadScore가 숫자가 아님: {} -> {}", instance.getInstanceId(), loadScore);
                    return 100.0;
                })
                .doOnError(error -> log.error("부하점수 조회 실패 ({}): {}", instance.getInstanceId(), error.getMessage()))
                .onErrorReturn(100.0);
    }

    private List<ServiceInstance> getFallbackInstances() {
        return new ArrayList<>(staticInstances);
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
     * Actuator health 엔드포인트로 헬스체크 + Redis 저장
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

                            // Redis에 헬스 상태 저장
                            saveHealthStatusToRedis(instance.getInstanceId(), isHealthy)
                                    .subscribe();

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

                                // Redis에 DOWN 상태 저장
                                saveHealthStatusToRedis(instance.getInstanceId(), false)
                                        .subscribe();
                            }
                        }
                );
    }

    /**
     * 메트릭 수집 + Redis 저장
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

                            // Redis에 메트릭 저장
                            saveMetricsToRedis(instanceId, metrics)
                                    .subscribe();
                        },
                        error -> {
                            log.error("인스턴스 {}:{} 메트릭 수집 실패: {}",
                                    instance.getHost(), instance.getPort(),
                                    error.getMessage());

                            // Redis에서 메트릭 제거
                            removeMetricsFromRedis(instance.getInstanceId())
                                    .subscribe();
                        }
                );
    }

    /**
     * Redis에 헬스 상태 저장 (타입 안전한 방식)
     */
    private Mono<Void> saveHealthStatusToRedis(String instanceId, boolean isHealthy) {
        if (reactiveRedisTemplate == null) return Mono.empty();

        String key = HEALTH_KEY_PREFIX + instanceId;

        // 🔥 단순한 Map으로 저장 (타입 정보 제거)
        Map<String, Object> healthData = new HashMap<>();
        healthData.put("isHealthy", isHealthy);
        healthData.put("timestamp", System.currentTimeMillis());  // Long으로 직접 저장

        return reactiveRedisTemplate.opsForValue()
                .set(key, healthData, Duration.ofSeconds(CACHE_TTL_SECONDS))
                .doOnSuccess(v -> log.debug("헬스 상태 Redis 저장 성공: {} -> {}", instanceId, isHealthy))
                .doOnError(e -> log.error("헬스 상태 Redis 저장 실패: {} -> {}", instanceId, e.getMessage()))
                .then();
    }

    /**
     * Redis에 메트릭 저장 (타입 안전한 방식)
     */
    private Mono<Void> saveMetricsToRedis(String instanceId, Map<String, Object> metrics) {
        if (reactiveRedisTemplate == null) return Mono.empty();

        String key = METRICS_KEY_PREFIX + instanceId;

        // 🔥 새로운 Map으로 복사하여 타입 문제 방지
        Map<String, Object> safeMetrics = new HashMap<>();
        metrics.forEach((k, v) -> {
            if (v instanceof Number || v instanceof String || v instanceof Boolean) {
                safeMetrics.put(k, v);  // 기본 타입만 저장
            }
        });
        safeMetrics.put("timestamp", System.currentTimeMillis());  // Long으로 직접 저장

        return reactiveRedisTemplate.opsForValue()
                .set(key, safeMetrics, Duration.ofSeconds(CACHE_TTL_SECONDS))
                .doOnSuccess(v -> log.debug("메트릭 Redis 저장 성공: {} -> keys: {}", instanceId, safeMetrics.keySet()))
                .doOnError(e -> log.error("메트릭 Redis 저장 실패: {} -> {}", instanceId, e.getMessage()))
                .then();
    }

    /**
     * Redis에서 메트릭 제거
     */
    private Mono<Void> removeMetricsFromRedis(String instanceId) {
        if (reactiveRedisTemplate == null) return Mono.empty();

        String metricsKey = METRICS_KEY_PREFIX + instanceId;
        String healthKey = HEALTH_KEY_PREFIX + instanceId;

        return reactiveRedisTemplate.delete(metricsKey, healthKey).then();
    }

    /**
     * Redis에서 건강한 인스턴스 목록 조회
     */
    private Mono<List<LoadBalancedServiceBatchInstance>> getHealthyInstancesFromRedis() {
        if (reactiveRedisTemplate == null) {
            return Mono.just(staticInstances.stream()
                    .filter(instance -> instance.isHealthy.get())
                    .collect(Collectors.toList()));
        }

        List<Mono<LoadBalancedServiceBatchInstance>> healthChecks = staticInstances.stream()
                .map(this::checkInstanceHealthInRedis)
                .collect(Collectors.toList());

        return Flux.fromIterable(healthChecks)
                .flatMap(mono -> mono)
                .filter(Objects::nonNull)
                .collectList();
    }

    /**
     * Redis에서 개별 인스턴스 헬스 상태 확인
     */
    private Mono<LoadBalancedServiceBatchInstance> checkInstanceHealthInRedis(LoadBalancedServiceBatchInstance instance) {
        String healthKey = HEALTH_KEY_PREFIX + instance.getInstanceId();

        return reactiveRedisTemplate.opsForValue()
                .get(healthKey)
                .cast(Map.class)
                .flatMap(healthData -> {
                    Boolean isHealthy = (Boolean) healthData.get("isHealthy");
                    if (Boolean.TRUE.equals(isHealthy)) {
                        instance.isHealthy.set(true);
                        return Mono.just(instance);
                    }
                    instance.isHealthy.set(false);
                    return Mono.empty();
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // Redis에 데이터가 없으면 로컬 상태 사용
                    return instance.isHealthy.get() ? Mono.just(instance) : Mono.empty();
                }))
                .onErrorResume(error -> {
                    log.error("Redis에서 헬스 상태 조회 실패 ({}): {}", instance.getInstanceId(), error.getMessage());
                    return instance.isHealthy.get() ? Mono.just(instance) : Mono.empty();
                });
    }


    /**
     * 🔥 Redis에서 모든 인스턴스의 메트릭 정보 조회 (비동기 버전 사용)
     */
    public Mono<Map<String, Map<String, Object>>> getAllMetricsFromRedis() {
        if (reactiveRedisTemplate == null) {
            return Mono.just(new HashMap<>());
        }

        List<String> keys = staticInstances.stream()
                .map(instance -> METRICS_KEY_PREFIX + instance.getInstanceId())
                .collect(Collectors.toList());

        return reactiveRedisTemplate.opsForValue()
                .multiGet(keys)
                .map(values -> {
                    Map<String, Map<String, Object>> allMetrics = new HashMap<>();

                    for (int i = 0; i < keys.size() && i < values.size(); i++) {
                        if (values.get(i) != null) {
                            String instanceId = keys.get(i).replace(METRICS_KEY_PREFIX, "");
                            allMetrics.put(instanceId, (Map<String, Object>) values.get(i));
                        }
                    }

                    return allMetrics;
                });
    }

    /**
     * 🔥 로드밸런서 상태 요약 (비동기 메서드 활용)
     */
    public Mono<Map<String, Object>> getDetailedStatusFromRedis() {
        return getAllMetricsFromRedis()
                .map(allMetrics -> {
                    Map<String, Object> status = new HashMap<>();

                    List<Map<String, Object>> instances = staticInstances.stream()
                            .map(instance -> {
                                Map<String, Object> instanceInfo = new HashMap<>();
                                instanceInfo.put("instanceId", instance.getInstanceId());
                                instanceInfo.put("host", instance.getHost());
                                instanceInfo.put("port", instance.getPort());
                                instanceInfo.put("isHealthy", instance.isHealthy.get());
                                instanceInfo.put("uri", instance.getUri().toString());

                                // Redis에서 메트릭 정보 추가
                                Map<String, Object> metrics = allMetrics.get(instance.getInstanceId());
                                if (metrics != null) {
                                    instanceInfo.put("loadScore", metrics.get("loadScore"));
                                    instanceInfo.put("cpuUsage", metrics.get("cpuUsage"));
                                    instanceInfo.put("memoryUsage", metrics.get("memoryUsage"));
                                    instanceInfo.put("activeThreads", metrics.get("activeThreads"));
                                    instanceInfo.put("responseTime", metrics.get("responseTime"));
                                    instanceInfo.put("requestCount", metrics.get("requestCount"));
                                    instanceInfo.put("lastUpdated", metrics.get("timestamp"));
                                    instanceInfo.put("dataSource", "REDIS");
                                } else {
                                    instanceInfo.put("loadScore", 100.0);
                                    instanceInfo.put("metricsStatus", "NO_REDIS_DATA");
                                    instanceInfo.put("dataSource", "LOCAL");
                                }

                                return instanceInfo;
                            })
                            .collect(Collectors.toList());

                    // 전체 상태 요약
                    long healthyCount = staticInstances.stream()
                            .mapToLong(instance -> instance.isHealthy.get() ? 1 : 0)
                            .sum();

                    long metricsAvailableCount = allMetrics.size();

                    status.put("serviceId", serviceId);
                    status.put("totalInstances", staticInstances.size());
                    status.put("healthyInstances", healthyCount);
                    status.put("metricsAvailableInstances", metricsAvailableCount);
                    status.put("instances", instances);
                    status.put("timestamp", System.currentTimeMillis());
                    status.put("cacheSource", "REDIS");
                    status.put("redisEnabled", reactiveRedisTemplate != null);

                    // 🔥 최적 인스턴스 정보 (비동기 메서드 사용하지만 동기로 변환)
                    Optional<LoadBalancedServiceBatchInstance> bestInstance = staticInstances.stream()
                            .filter(instance -> instance.isHealthy.get())
                            .min(Comparator.comparingDouble(instance ->
                                    getInstanceLoadScoreAsync(instance)
                                            .block(Duration.ofSeconds(3))  // 1초 타임아웃으로 동기 변환
                                            .doubleValue()));

                    if (bestInstance.isPresent()) {
                        Map<String, Object> bestInfo = new HashMap<>();
                        bestInfo.put("instanceId", bestInstance.get().getInstanceId());
                        // 🔥 비동기 메서드를 동기로 변환하여 사용
                        Double loadScore = getInstanceLoadScoreAsync(bestInstance.get())
                                .block(Duration.ofSeconds(3));
                        bestInfo.put("loadScore", loadScore);
                        status.put("currentBestInstance", bestInfo);
                    }

                    return status;
                });
    }

    /**
     * Blocking 버전 (기존 호환성)
     */
    public Map<String, Map<String, Object>> getAllMetrics() {
        if (reactiveRedisTemplate == null) {
            return new HashMap<>();
        }

        return getAllMetricsFromRedis()
                .block(Duration.ofSeconds(2));
    }

    public Map<String, Object> getDetailedStatus() {
        if (reactiveRedisTemplate == null) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("error", "Redis가 비활성화되어 있습니다.");
            fallback.put("redisEnabled", false);
            return fallback;
        }

        return getDetailedStatusFromRedis()
                .block(Duration.ofSeconds(2));
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
                    "metricsEnabled", "true",
                    "cacheType", "REDIS"
            );
        }
    }
}
