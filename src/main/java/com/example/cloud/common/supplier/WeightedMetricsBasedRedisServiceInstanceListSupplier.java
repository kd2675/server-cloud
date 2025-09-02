package com.example.cloud.common.supplier;

import com.example.cloud.common.instance.LoadBalancedServiceBatchInstance;
import com.example.cloud.common.instance.WeightedInstance;
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

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class WeightedMetricsBasedRedisServiceInstanceListSupplier implements ServiceInstanceListSupplier {
    private final String serviceId = "service-batch";
    private final WebClient webClient;
    private final List<LoadBalancedServiceBatchInstance> staticInstances;

    // Redis 캐시 사용
    private final ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;
    private final long CACHE_TTL_SECONDS = 30; // 30초 TTL

    // Redis 키 패턴
    private static final String METRICS_KEY_PREFIX = "loadbalancer:metrics:";
    private static final String HEALTH_KEY_PREFIX = "loadbalancer:health:";

    // 🔥 가중치 설정
    private static final double MIN_WEIGHT = 1.0;
    private static final double MAX_WEIGHT = 10.0;
    private static final String LOAD_BALANCING_STRATEGY = "WEIGHTED"; // WEIGHTED, BEST_ONLY, THRESHOLD

    public WeightedMetricsBasedRedisServiceInstanceListSupplier(
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

        log.info("🎯 WeightedMetricsBasedLoadBalancer 초기화 완료 (Redis: {}, 전략: {}) - {}:{}|{}:{}|{}:{}",
                reactiveRedisTemplate != null,
                LOAD_BALANCING_STRATEGY,
                serverHost, serverPort1, serverHost, serverPort2, serverHost, serverPort3);

        // 백그라운드 모니터링 시작
        startMetricsAndHealthMonitoring();
    }

    @Override
    public String getServiceId() {
        return serviceId;
    }

    /**
     * 🚀 가중 기반 로드밸런싱 메인 메서드
     */
    @Override
    public Flux<List<ServiceInstance>> get() {
        return getHealthyInstancesFromRedis()
                .flatMap(this::createWeightedInstanceListAsync)
                .onErrorResume(error -> {
                    log.warn("Redis에서 인스턴스 조회 실패, fallback 사용: {}", error.getMessage());
                    return Mono.just(getFallbackInstances());
                })
                .flux();
    }

    /**
     * 🔥 가중 기반 인스턴스 리스트 생성 (비동기) - 타입 수정
     */
    private Mono<List<ServiceInstance>> createWeightedInstanceListAsync(List<LoadBalancedServiceBatchInstance> healthyInstances) {
        if (healthyInstances.isEmpty()) {
            log.warn("건강한 service-batch 인스턴스가 없습니다. fallback 인스턴스를 반환합니다.");
            return Mono.just(getFallbackInstances());
        }

        return Flux.fromIterable(healthyInstances)
                .flatMap(instance ->
                        getInstanceLoadScoreAsync(instance)
                                // 🔥 LoadBalancedServiceBatchInstance를 ServiceInstance로 캐스팅
                                .map(score -> Map.entry((ServiceInstance) instance, score))
                )
                .collectList()
                .map(this::createWeightedInstanceList)
                .doOnError(error -> log.error("가중 기반 인스턴스 선택 실패", error))
                .onErrorResume(error -> {
                    log.warn("에러 발생으로 인한 Fallback 사용: {}", error.getMessage());
                    return Mono.just(getFallbackInstances());  // 🔥 이제 에러 시에만 실행!
                });
    }

    /**
     * 🎯 부하점수 기반 가중 리스트 생성 - 제네릭 타입 명시
     */
    private List<ServiceInstance> createWeightedInstanceList(List<Map.Entry<ServiceInstance, Double>> entries) {
        if (entries.isEmpty()) {
            log.warn("entries in null");
            return getFallbackInstances();
        }

        // 🔥 타입 안전한 가중치 계산
        List<WeightedInstance> weightedInstances = entries.stream()
                .map(entry -> {
                    ServiceInstance instance = entry.getKey();
                    double loadScore = entry.getValue();

                    // 가중치 계산: 부하점수가 낮을수록 높은 가중치
                    double weight = calculateWeight(loadScore);

                    return new WeightedInstance(instance, loadScore, weight);
                })
                .sorted(Comparator.comparingDouble(WeightedInstance::loadScore)) // 점수순 정렬
                .collect(Collectors.toList());

        // 가중치에 따른 인스턴스 복제 리스트 생성
        List<ServiceInstance> weightedList = createWeightedList(weightedInstances);

        // 로그 출력
        logWeightedSelection(weightedInstances, weightedList.size());

        return weightedList;
    }

    /**
     * 🔧 부하점수를 가중치로 변환 - NaN 안전 처리
     */
    private double calculateWeight(double loadScore) {
        // 🔥 NaN, 무한대, 음수 값 사전 처리
        if (Double.isNaN(loadScore) || Double.isInfinite(loadScore) || loadScore < 0) {
            return MIN_WEIGHT; // 기본 최소 가중치 반환
        }

        // 기본 역수 방식: 낮은 점수 = 높은 가중치
        double baseWeight = 100.0 / Math.max(loadScore, 10.0);

        // 최소/최대 가중치 제한
        return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, baseWeight));
    }

    /**
     * 🔧 가중치 기반 인스턴스 리스트 생성
     */
    private List<ServiceInstance> createWeightedList(List<WeightedInstance> weightedInstances) {
        List<ServiceInstance> result = new ArrayList<>();

        for (WeightedInstance wi : weightedInstances) {
            int copies = (int) Math.round(wi.weight());
            for (int i = 0; i < copies; i++) {
                result.add(wi.instance());
            }
        }

        // 최소 1개는 보장
        if (result.isEmpty() && !weightedInstances.isEmpty()) {
            result.add(weightedInstances.get(0).instance());
        }

        return result;
    }

    /**
     * 🔧 가중 선택 결과 로깅 - 포맷팅 수정
     */
    private void logWeightedSelection(List<WeightedInstance> weightedInstances, int totalCopies) {
        String weightInfo = weightedInstances.stream()
                .map(wi -> {
                    int copies = (int) Math.round(wi.weight());
                    double percentage = totalCopies > 0 ? (copies * 100.0) / totalCopies : 0.0;
                    return String.format("%s(점수:%.1f,가중:%.1f,복사:%d,비율:%.1f%%)",
                            wi.instance().getInstanceId(),
                            wi.loadScore(),
                            wi.weight(),
                            copies,
                            percentage);
                })
                .collect(Collectors.joining(" | "));

        log.info("🎯 가중 기반 로드밸런싱: {} | 총 인스턴스: {}", weightInfo, totalCopies);

        // 효율성 평가
        double avgLoadScore = weightedInstances.stream()
                .mapToDouble(wi -> wi.loadScore())
                .average()
                .orElse(100.0);

        String efficiency = avgLoadScore < 30 ? "EXCELLENT" :
                avgLoadScore < 50 ? "GOOD" :
                        avgLoadScore < 70 ? "FAIR" : "POOR";

        // 🔥 Java 스타일 포맷팅으로 수정
        log.info("🔍 로드밸런싱 효율성: {} (평균점수: {})", efficiency, String.format("%.1f", avgLoadScore));
    }

    /**
     * 🔥 비동기로 부하점수 조회 - 매개변수 타입 수정
     */
    private Mono<Double> getInstanceLoadScoreAsync(LoadBalancedServiceBatchInstance instance) {
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
                .doOnNext(tick -> log.info("부하점수 : {} -> {}", instance.getInstanceId(), tick))
                .doOnError(error -> log.error("부하점수 조회 실패 ({}): {}", instance.getInstanceId(), error.getMessage()))
                .onErrorReturn(100.0);
    }

    /**
     * 🔧 Fallback 인스턴스 (타입 안전)
     */
    private List<ServiceInstance> getFallbackInstances() {
        if (!staticInstances.isEmpty()) {
            // 🔥 LoadBalancedServiceBatchInstance를 ServiceInstance로 캐스팅
            List<ServiceInstance> fallbackList = staticInstances.stream()
                    .map(instance -> (ServiceInstance) instance)
                    .collect(Collectors.toList());

            log.info("🔄 Fallback 인스턴스 사용: {} 개", fallbackList.size());
            return fallbackList;
        }
        return new ArrayList<>();
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
                log.error("인스턴스 {} 모니터링 실패: {}", instance.getInstanceId(), e.getMessage());
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

        Map<String, Object> healthData = new HashMap<>();
        healthData.put("isHealthy", isHealthy);
        healthData.put("timestamp", System.currentTimeMillis());

        return reactiveRedisTemplate.opsForValue()
                .set(key, healthData, Duration.ofSeconds(CACHE_TTL_SECONDS))
                .doOnSuccess(v -> log.info("헬스 상태 Redis 저장 성공: {} -> {}", instanceId, isHealthy))
                .doOnError(e -> log.error("헬스 상태 Redis 저장 실패: {} -> {}", instanceId, e.getMessage()))
                .then();
    }

    /**
     * Redis에 메트릭 저장 (타입 안전한 방식)
     */
    private Mono<Void> saveMetricsToRedis(String instanceId, Map<String, Object> metrics) {
        if (reactiveRedisTemplate == null) return Mono.empty();

        String key = METRICS_KEY_PREFIX + instanceId;

        Map<String, Object> safeMetrics = new HashMap<>();
        metrics.forEach((k, v) -> {
            if (v instanceof Number || v instanceof String || v instanceof Boolean) {
                safeMetrics.put(k, v);
            }
        });
        safeMetrics.put("timestamp", System.currentTimeMillis());

        return reactiveRedisTemplate.opsForValue()
                .set(key, safeMetrics, Duration.ofSeconds(CACHE_TTL_SECONDS))
                .doOnSuccess(v -> log.info("메트릭 Redis 저장 성공: {} -> keys: {}", instanceId, safeMetrics.keySet()))
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
     * Redis에서 건강한 인스턴스 목록 조회 - 타입 안전
     */
    private Mono<List<LoadBalancedServiceBatchInstance>> getHealthyInstancesFromRedis() {
        if (reactiveRedisTemplate == null) {
            // Redis가 없으면 로컬 상태 기반으로 필터링
            List<LoadBalancedServiceBatchInstance> healthyInstances = staticInstances.stream()
                    .filter(instance -> instance.isHealthy.get())
                    .collect(Collectors.toList());

            log.error("Redis 미사용 - 로컬 건강한 인스턴스: {}/{}", healthyInstances.size(), staticInstances.size());
            return Mono.just(healthyInstances);
        }

        // Redis 상태 확인
        List<Mono<LoadBalancedServiceBatchInstance>> healthChecks = staticInstances.stream()
                .map(this::checkInstanceHealthInRedis)
                .collect(Collectors.toList());

        return Flux.fromIterable(healthChecks)
                .flatMap(mono -> mono)
                .filter(Objects::nonNull)
                .collectList()
                .doOnNext(healthyList ->
                        log.info("Redis 기반 건강한 인스턴스: {}/{}", healthyList.size(), staticInstances.size()));
    }

    /**
     * 🔥 Redis에서 개별 인스턴스 헬스 상태 확인 - 완전 NPE 방지 버전
     */
    private Mono<LoadBalancedServiceBatchInstance> checkInstanceHealthInRedis(LoadBalancedServiceBatchInstance instance) {
        // 🔥 reactiveRedisTemplate이 null이면 즉시 로컬 상태 반환
        if (reactiveRedisTemplate == null) {
            return instance.isHealthy.get() ? Mono.just(instance) : Mono.empty();
        }

        String healthKey = HEALTH_KEY_PREFIX + instance.getInstanceId();

        try {
            // 🔥 NPE 방지: opsForValue()도 null일 수 있음
            if (reactiveRedisTemplate.opsForValue() == null) {
                log.warn("ReactiveValueOperations가 null - 로컬 상태 사용: {}", instance.getInstanceId());
                return instance.isHealthy.get() ? Mono.just(instance) : Mono.empty();
            }

            // 🔥 null 안전 처리를 위해 defer 사용
            return Mono.defer(() -> {
                        try {
                            Mono<Object> redisMono = reactiveRedisTemplate.opsForValue().get(healthKey);
                            // 🔥 Redis 결과가 null인 경우 처리
                            if (redisMono == null) {
                                return instance.isHealthy.get() ? Mono.just(instance) : Mono.empty();
                            }
                            return redisMono;
                        } catch (Exception e) {
                            log.error("Redis get() 호출 중 예외: {}", e.getMessage());
                            return Mono.empty();
                        }
                    })
                    .cast(Map.class)
                    .map(healthData -> {
                        Boolean isHealthy = (Boolean) healthData.get("isHealthy");
                        if (Boolean.TRUE.equals(isHealthy)) {
                            instance.isHealthy.set(true);
                            return instance;
                        }
                        return null;
                    })
                    .filter(Objects::nonNull) // null 값 필터링
                    .switchIfEmpty(Mono.defer(() -> {
                        // 건강하지 않거나 데이터가 없는 경우
                        log.warn("Redis에서 헬스 데이터 없음 ({}), 로컬 상태 사용", instance.getInstanceId());
                        return instance.isHealthy.get() ? Mono.just(instance) : Mono.empty();
                    }))
                    .onErrorResume(error -> {
                        log.error("Redis에서 헬스 상태 조회 실패 ({}): {}", instance.getInstanceId(), error.getMessage());
                        return instance.isHealthy.get() ? Mono.just(instance) : Mono.empty();
                    });
        } catch (Exception e) {
            log.error("Redis 헬스체크 중 예외 발생 ({}): {}", instance.getInstanceId(), e.getMessage());
            return instance.isHealthy.get() ? Mono.just(instance) : Mono.empty();
        }
    }

    /**
     * 🔥 Redis에서 모든 인스턴스의 메트릭 정보 조회
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
     * 🔥 로드밸런서 상태 요약 (가중치 정보 포함)
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
                                    double loadScore = metrics.get("loadScore") instanceof Number ?
                                            ((Number) metrics.get("loadScore")).doubleValue() : 100.0;
                                    double weight = calculateWeight(loadScore);

                                    instanceInfo.put("loadScore", loadScore);
                                    instanceInfo.put("weight", weight);
                                    instanceInfo.put("cpuUsage", metrics.get("cpuUsage"));
                                    instanceInfo.put("memoryUsage", metrics.get("memoryUsage"));
                                    instanceInfo.put("activeThreads", metrics.get("activeThreads"));
                                    instanceInfo.put("responseTime", metrics.get("responseTime"));
                                    instanceInfo.put("requestCount", metrics.get("requestCount"));
                                    instanceInfo.put("lastUpdated", metrics.get("timestamp"));
                                    instanceInfo.put("dataSource", "REDIS");
                                } else {
                                    instanceInfo.put("loadScore", 100.0);
                                    instanceInfo.put("weight", MIN_WEIGHT);
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

                    status.put("serviceId", serviceId);
                    status.put("strategy", LOAD_BALANCING_STRATEGY);
                    status.put("totalInstances", staticInstances.size());
                    status.put("healthyInstances", healthyCount);
                    status.put("metricsAvailableInstances", allMetrics.size());
                    status.put("instances", instances);
                    status.put("timestamp", System.currentTimeMillis());
                    status.put("cacheSource", "REDIS");
                    status.put("redisEnabled", reactiveRedisTemplate != null);
                    status.put("weightRange", Map.of("min", MIN_WEIGHT, "max", MAX_WEIGHT));

                    return status;
                });
    }

    /**
     * 🔥 동기 버전 메트릭 조회 - 타입 안전
     */
    public Map<String, Map<String, Object>> getAllMetrics() {
        Map<String, Map<String, Object>> result = getAllMetricsFromRedis()
                .block(Duration.ofSeconds(2));
        return result != null ? result : new HashMap<>();
    }

    public Map<String, Object> getDetailedStatus() {
        Map<String, Object> result = getDetailedStatusFromRedis()
                .block(Duration.ofSeconds(2));
        return result != null ? result : new HashMap<>();
    }

    /**
         * 🔧 가중치 정보를 담는 내부 클래스
         */
}