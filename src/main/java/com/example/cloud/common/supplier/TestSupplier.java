package com.example.cloud.common.supplier;

import com.example.cloud.common.instance.LoadBalancedServiceBatchInstance;
import com.example.cloud.common.instance.WeightedInstance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class TestSupplier implements ExtendedServiceInstanceListSupplier {
    private final String serviceId = "service-batch";
    private final WebClient webClient;
    private final DiscoveryClient discoveryClient;

    // 백업(정적) 인스턴스
    private final List<LoadBalancedServiceBatchInstance> backupInstances;

    // Redis 캐시 사용
    private final ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;
    private static final long CACHE_TTL_SECONDS = 30;

    // Redis 키 패턴
    private static final String METRICS_KEY_PREFIX = "loadbalancer:metrics:";
    private static final String HEALTH_KEY_PREFIX = "loadbalancer:health:";

    // 가중치 설정
    private static final double MIN_WEIGHT = 1.0;
    private static final double MAX_WEIGHT = 10.0;
    private static final String STRATEGY = "WEIGHTED";

    public TestSupplier(
            ConfigurableApplicationContext context,
            DiscoveryClient discoveryClient,
            @Autowired(required = false) ReactiveRedisTemplate<String, Object> reactiveRedisTemplate) {

        this.discoveryClient = discoveryClient;
        this.reactiveRedisTemplate = reactiveRedisTemplate;

        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();

        // 백업 인스턴스 읽기
        String backupHost = context.getEnvironment().getProperty("path.service.batch.host");
        Integer backupPort1 = context.getEnvironment().getProperty("path.service.batch.port3", Integer.class, null);
//        Integer backupPort2 = context.getEnvironment().getProperty("path.service.batch.backupPort2", Integer.class, null);
//        Integer backupPort3 = context.getEnvironment().getProperty("path.service.batch.backupPort3", Integer.class, null);

        List<LoadBalancedServiceBatchInstance> backups = new ArrayList<>();
        if (backupHost != null) {
            if (backupPort1 != null) backups.add(new LoadBalancedServiceBatchInstance("service-batch-backup-1", backupHost, backupPort1));
//            if (backupPort2 != null) backups.add(new LoadBalancedServiceBatchInstance("service-batch-backup-2", backupHost, backupPort2));
//            if (backupPort3 != null) backups.add(new LoadBalancedServiceBatchInstance("service-batch-backup-3", backupHost, backupPort3));
        }
        this.backupInstances = Collections.unmodifiableList(backups);

        log.info("🎯 Supplier 초기화(Eureka+Redis+Weighted) redisEnabled={}, strategy={}, backups={}",
                reactiveRedisTemplate != null,
                STRATEGY,
                backupInstances.stream().map(i -> i.getHost() + ":" + i.getPort()).collect(Collectors.joining(","))
        );

        // 백업 인스턴스 헬스/메트릭 모니터링
        startBackupMonitoring();
    }

    @Override
    public String getServiceId() {
        return serviceId;
    }

    /**
     * 메인: 유레카 → Redis 헬스 필터 → 가중치 → 백업 대체
     */
    @Override
    public Flux<List<ServiceInstance>> get() {
        return Mono.fromCallable(this::fetchEurekaInstances)
                .flatMap(eurekaList -> {
                    if (eurekaList.isEmpty()) {
                        List<ServiceInstance> backups = toServiceInstances(backupInstances);
                        log.warn("⚠️ Eureka 인스턴스 없음. Backup 사용: {}", backups.size());
                        return Mono.just(backups);
                    }
                    return filterHealthyByRedis(eurekaList)
                            .flatMap(healthy -> {
                                if (healthy.isEmpty()) {
                                    List<ServiceInstance> backups = toServiceInstances(backupInstances);
                                    log.warn("⚠️ 건강 후보 0. Backup 사용: {}", backups.size());
                                    return Mono.just(backups);
                                }
                                return createWeightedAsync(healthy);
                            });
                })
                .onErrorResume(e -> {
                    log.warn("get() 중 에러. Backup 사용: {}", e.getMessage());
                    List<ServiceInstance> backups = toServiceInstances(backupInstances);
                    return Mono.just(!backups.isEmpty() ? backups : Collections.emptyList());
                })
                .flux();
    }

    // Eureka에서 동적 후보 조회 → LoadBalancedServiceBatchInstance로 변환
    private List<LoadBalancedServiceBatchInstance> fetchEurekaInstances() {
        try {
            List<ServiceInstance> list = discoveryClient.getInstances(serviceId);
            List<LoadBalancedServiceBatchInstance> result = list.stream()
                    .map(si -> new LoadBalancedServiceBatchInstance(
                            si.getInstanceId() != null ? si.getInstanceId() : (si.getHost() + ":" + si.getPort()),
                            si.getHost(),
                            si.getPort()
                    ))
                    .collect(Collectors.toList());
            log.info("🎯 Eureka 발견 {} 인스턴스: {}", serviceId, result.size());
            return result;
        } catch (Exception e) {
            log.error("Eureka 조회 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // Redis 헬스 데이터 기반 건강 후보 선별(데이터 없으면 후보 유지)
    private Mono<List<LoadBalancedServiceBatchInstance>> filterHealthyByRedis(List<LoadBalancedServiceBatchInstance> candidates) {
        if (reactiveRedisTemplate == null) {
            log.warn("Redis 미사용 - 후보 {} 유지", candidates.size());
            return Mono.just(candidates);
        }
        List<Mono<LoadBalancedServiceBatchInstance>> checks = candidates.stream()
                .map(this::checkHealthInRedis)
                .collect(Collectors.toList());

        return Flux.fromIterable(checks)
                .flatMap(m -> m)
                .filter(Objects::nonNull)
                .collectList()
                .doOnNext(list -> log.info("Redis 기반 건강 후보: {}/{}", list.size(), candidates.size()));
    }

    private Mono<LoadBalancedServiceBatchInstance> checkHealthInRedis(LoadBalancedServiceBatchInstance inst) {
        if (reactiveRedisTemplate == null) return Mono.just(inst);
        String key = HEALTH_KEY_PREFIX + inst.getInstanceId();
        try {
            return Mono.defer(() -> {
                        try {
                            Mono<Object> m = reactiveRedisTemplate.opsForValue().get(key);
                            return m == null ? Mono.just(inst) : m;
                        } catch (Exception e) {
                            log.error("Redis get() 예외: {}", e.getMessage());
                            return Mono.just(inst);
                        }
                    })
                    .cast(Map.class)
                    .map(health -> {
                        Boolean up = (Boolean) health.get("isHealthy");
                        return Boolean.FALSE.equals(up) ? null : inst;
                    })
                    .filter(Objects::nonNull)
                    .switchIfEmpty(Mono.just(inst)) // 데이터 없음 → 유지
                    .onErrorResume(e -> {
                        log.error("헬스 조회 실패({}): {}", inst.getInstanceId(), e.getMessage());
                        return Mono.just(inst);
                    });
        } catch (Exception e) {
            log.error("헬스체크 예외({}): {}", inst.getInstanceId(), e.getMessage());
            return Mono.just(inst);
        }
    }

    // 가중 리스트 생성(메트릭 loadScore 사용)
    private Mono<List<ServiceInstance>> createWeightedAsync(List<LoadBalancedServiceBatchInstance> healthy) {
        return Flux.fromIterable(healthy)
                .flatMap(inst -> getLoadScore(inst).map(score -> Map.entry((ServiceInstance) inst, score)))
                .collectList()
                .map(this::toWeightedList)
                .onErrorResume(e -> {
                    log.warn("가중 리스트 생성 실패. Backup 사용: {}", e.getMessage());
                    return Mono.just(toServiceInstances(backupInstances));
                });
    }

    private List<ServiceInstance> toWeightedList(List<Map.Entry<ServiceInstance, Double>> entries) {
        if (entries.isEmpty()) return toServiceInstances(backupInstances);

        List<WeightedInstance> weighted = entries.stream()
                .map(e -> new WeightedInstance(e.getKey(), e.getValue(), weightOf(e.getValue())))
                .sorted(Comparator.comparingDouble(WeightedInstance::loadScore))
                .collect(Collectors.toList());

        List<ServiceInstance> out = new ArrayList<>();
        for (WeightedInstance wi : weighted) {
            int copies = (int) Math.round(wi.weight());
            for (int i = 0; i < copies; i++) out.add(wi.instance());
        }
        if (out.isEmpty() && !weighted.isEmpty()) out.add(weighted.get(0).instance());

        logWeighted(weighted, out.size());
        return out;
    }

    private void logWeighted(List<WeightedInstance> list, int total) {
        String s = list.stream().map(wi -> {
            int c = (int) Math.round(wi.weight());
            double pct = total > 0 ? (c * 100.0) / total : 0.0;
            return String.format("%s(점수:%.1f,가중:%.1f,복사:%d,비율:%.1f%%)",
                    wi.instance().getInstanceId(), wi.loadScore(), wi.weight(), c, pct);
        }).collect(Collectors.joining(" | "));
        double avg = list.stream().mapToDouble(WeightedInstance::loadScore).average().orElse(100.0);
        String eff = avg < 30 ? "EXCELLENT" : avg < 50 ? "GOOD" : avg < 70 ? "FAIR" : "POOR";
        log.info("🎯 가중 로드밸런싱: {} | 총:{} | 효율:{}, 평균:{}", s, total, eff, String.format("%.1f", avg));
    }

    private double weightOf(double loadScore) {
        if (Double.isNaN(loadScore) || Double.isInfinite(loadScore) || loadScore < 0) return MIN_WEIGHT;
        double base = 100.0 / Math.max(loadScore, 10.0);
        return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, base));
    }

    private Mono<Double> getLoadScore(LoadBalancedServiceBatchInstance inst) {
        if (reactiveRedisTemplate == null) return Mono.just(100.0);
        String key = METRICS_KEY_PREFIX + inst.getInstanceId();
        return reactiveRedisTemplate.opsForValue()
                .get(key)
                .cast(Map.class)
                .timeout(Duration.ofSeconds(3))
                .map(m -> {
                    Object v = m.get("loadScore");
                    if (v instanceof Number) return ((Number) v).doubleValue();
                    log.error("loadScore 타입 오류: {} -> {}", inst.getInstanceId(), v);
                    return 100.0;
                })
                .doOnNext(s -> log.info("부하점수 {} -> {}", inst.getInstanceId(), s))
                .onErrorReturn(100.0);
    }

    private List<ServiceInstance> toServiceInstances(List<LoadBalancedServiceBatchInstance> list) {
        return list.stream().map(i -> (ServiceInstance) i).collect(Collectors.toList());
    }

    // 백업 인스턴스 모니터링(헬스/메트릭 → Redis 저장)
    private void startBackupMonitoring() {
        Flux.interval(Duration.ofSeconds(15))
                .doOnNext(tick -> {
                    if (backupInstances.isEmpty()) return;
                    log.info("백업 인스턴스 모니터링 ({})", tick);
                    monitorOnce();
                })
                .subscribe();
    }

    private void monitorOnce() {
        backupInstances.parallelStream().forEach(inst -> {
            try {
                checkHealth(inst);
                if (inst.isHealthy.get()) collectMetrics(inst);
            } catch (Exception e) {
                log.error("백업 모니터링 실패 {}: {}", inst.getInstanceId(), e.getMessage());
            }
        });
    }

    private void checkHealth(LoadBalancedServiceBatchInstance inst) {
        String url = inst.getUri() + "/actuator/health";
        webClient.get().uri(url)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(5))
                .subscribe(
                        resp -> {
                            boolean up = resp.getStatusCode().is2xxSuccessful();
                            boolean was = inst.isHealthy.getAndSet(up);
                            saveHealth(inst.getInstanceId(), up).subscribe();
                            if (was != up) log.info("백업 {}:{} 상태 {} -> {}", inst.getHost(), inst.getPort(), was ? "UP" : "DOWN", up ? "UP" : "DOWN");
                        },
                        err -> {
                            if (inst.isHealthy.getAndSet(false)) {
                                log.warn("백업 헬스체크 실패 {}:{} - {}", inst.getHost(), inst.getPort(), err.getMessage());
                                saveHealth(inst.getInstanceId(), false).subscribe();
                            }
                        }
                );
    }

    private void collectMetrics(LoadBalancedServiceBatchInstance inst) {
        String url = inst.getUri() + "/service/batch/metrics/load";
        webClient.get().uri(url)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(8))
                .subscribe(
                        metrics -> saveMetrics(inst.getInstanceId(), metrics).subscribe(),
                        err -> {
                            log.error("백업 메트릭 수집 실패 {}:{} - {}", inst.getHost(), inst.getPort(), err.getMessage());
                            removeKeys(inst.getInstanceId()).subscribe();
                        }
                );
    }

    private Mono<Void> saveHealth(String instanceId, boolean up) {
        if (reactiveRedisTemplate == null) return Mono.empty();
        String key = HEALTH_KEY_PREFIX + instanceId;
        Map<String, Object> map = new HashMap<>();
        map.put("isHealthy", up);
        map.put("timestamp", System.currentTimeMillis());
        return reactiveRedisTemplate.opsForValue()
                .set(key, map, Duration.ofSeconds(CACHE_TTL_SECONDS))
                .doOnSuccess(v -> log.info("헬스 저장 {} -> {}", instanceId, up))
                .onErrorResume(e -> {
                    log.error("헬스 저장 실패 {}: {}", instanceId, e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> saveMetrics(String instanceId, Map<String, Object> metrics) {
        if (reactiveRedisTemplate == null) return Mono.empty();
        String key = METRICS_KEY_PREFIX + instanceId;
        Map<String, Object> safe = new HashMap<>();
        metrics.forEach((k, v) -> {
            if (v instanceof Number || v instanceof String || v instanceof Boolean) safe.put(k, v);
        });
        safe.put("timestamp", System.currentTimeMillis());
        return reactiveRedisTemplate.opsForValue()
                .set(key, safe, Duration.ofSeconds(CACHE_TTL_SECONDS))
                .doOnSuccess(v -> log.info("메트릭 저장 {} keys: {}", instanceId, safe.keySet()))
                .onErrorResume(e -> {
                    log.error("메트릭 저장 실패 {}: {}", instanceId, e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> removeKeys(String instanceId) {
        if (reactiveRedisTemplate == null) return Mono.empty();
        return reactiveRedisTemplate.delete(METRICS_KEY_PREFIX + instanceId, HEALTH_KEY_PREFIX + instanceId).then();
    }

    // 상세 상태(모니터링용)
    @Override
    public Map<String, Object> getDetailedStatus() {
        Map<String, Object> s = new HashMap<>();
        try {
            List<LoadBalancedServiceBatchInstance> eureka = fetchEurekaInstances();
            s.put("serviceId", serviceId);
            s.put("strategy", STRATEGY);
            s.put("eurekaCount", eureka.size());
            s.put("eurekaInstances", eureka.stream()
                    .map(i -> Map.of("id", i.getInstanceId(), "host", i.getHost(), "port", i.getPort(), "uri", i.getUri().toString()))
                    .collect(Collectors.toList()));
            s.put("backupInstances", backupInstances.stream()
                    .map(i -> Map.of("id", i.getInstanceId(), "host", i.getHost(), "port", i.getPort()))
                    .collect(Collectors.toList()));
            s.put("redisEnabled", reactiveRedisTemplate != null);
            s.put("timestamp", System.currentTimeMillis());
            return s;
        } catch (Exception e) {
            log.error("getDetailedStatus 실패: {}", e.getMessage());
            s.put("error", e.getMessage());
            s.put("serviceId", serviceId);
            s.put("timestamp", System.currentTimeMillis());
            return s;
        }
    }
}
