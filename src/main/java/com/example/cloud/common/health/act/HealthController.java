package com.example.cloud.common.health.act;

import com.example.cloud.common.supplier.MetricsBasedServiceInstanceListSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.core.response.base.dto.ResponseDTO;
import org.example.core.response.base.dto.ResponseDataDTO;
import org.example.core.response.base.exception.GeneralException;
import org.example.core.response.base.vo.Code;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/server/cloud/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController implements HealthIndicator {
    
    @Value("${server.url.member}")
    private String serverUrlMember;
    
    @Value("${server.url.service.batch}")
    private String serverUrlServiceBatch;
    
    @Value("${server.url.cocoin}")
    private String serverUrlCocoin;
    
    private final WebClient.Builder webClientBuilder;

    // LoadBalancer 상태 조회를 위한 Supplier 주입
    @Autowired(required = false)
    private MetricsBasedServiceInstanceListSupplier loadBalancerSupplier;

    private int count = 0;

    /**
     * 기본 헬스체크 - Gateway 자체 상태
     */
    @RequestMapping
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("timestamp", LocalDateTime.now());
        status.put("service", "Cloud Gateway");
        
        return ResponseEntity.ok(status);
    }

    /**
     * 전체 시스템 헬스체크 (내부 + 외부 서비스 + LoadBalancer)
     */
    @GetMapping("/full")
    public Mono<ResponseEntity<Map<String, Object>>> fullHealthCheck() {
        return Mono.fromCallable(this::checkInternalHealth)
                .flatMap(internalHealth -> {
                    if ("UP".equals(internalHealth.get("status"))) {
                        return checkExternalServices()
                                .map(externalHealth -> {
                                    Map<String, Object> fullHealth = new HashMap<>();
                                    fullHealth.put("gateway", internalHealth);
                                    fullHealth.put("external", externalHealth);
                                    fullHealth.put("loadBalancer", checkLoadBalancerHealth());
                                    fullHealth.put("overall", determineOverallStatus(internalHealth, externalHealth));
                                    fullHealth.put("timestamp", LocalDateTime.now());
                                    return ResponseEntity.ok(fullHealth);
                                });
                    } else {
                        Map<String, Object> result = new HashMap<>();
                        result.put("gateway", internalHealth);
                        result.put("loadBalancer", checkLoadBalancerHealth());
                        result.put("overall", "DOWN");
                        result.put("timestamp", LocalDateTime.now());
                        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result));
                    }
                });
    }

    /**
     * 내부 컴포넌트 헬스체크 (DB, Redis, Memory 등)
     */
    @GetMapping("/internal")
    public ResponseEntity<Map<String, Object>> internalHealthCheck() {
        Map<String, Object> result = checkInternalHealth();
        
        if ("UP".equals(result.get("status"))) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
        }
    }

    /**
     * 외부 서비스 헬스체크
     */
    @GetMapping("/external")
    public Mono<ResponseEntity<Map<String, Object>>> externalHealthCheck() {
        return checkExternalServices()
                .map(result -> {
                    boolean allUp = result.values().stream()
                            .allMatch(status -> "UP".equals(((Map<?, ?>) status).get("status")));
                    
                    if (allUp) {
                        return ResponseEntity.ok(result);
                    } else {
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
                    }
                });
    }

    /**
     * LoadBalancer 상태 및 메트릭 조회
     */
    @GetMapping("/loadbalancer")
    public ResponseEntity<Map<String, Object>> loadBalancerHealthCheck() {
        try {
            Map<String, Object> result = checkLoadBalancerHealth();
            
            String status = (String) result.get("status");
            if ("UP".equals(status)) {
                return ResponseEntity.ok(result);
            } else {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
            }
        } catch (Exception e) {
            log.error("LoadBalancer 헬스체크 실패", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "DOWN");
            error.put("error", "LoadBalancer 상태 조회 실패: " + e.getMessage());
            error.put("timestamp", LocalDateTime.now());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * LoadBalancer 상세 성능 정보
     */
    @GetMapping("/loadbalancer/performance")
    public ResponseEntity<Map<String, Object>> loadBalancerPerformance() {
        try {
            if (loadBalancerSupplier == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("status", "UNAVAILABLE");
                error.put("message", "LoadBalancer가 활성화되지 않았습니다.");
                error.put("timestamp", LocalDateTime.now());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
            }
            
            Map<String, Map<String, Object>> allMetrics = loadBalancerSupplier.getAllMetrics();
            Map<String, Object> performance = new HashMap<>();
            
            // 성능 지표 계산
            double avgLoadScore = allMetrics.values().stream()
                    .mapToDouble(metrics -> {
                        Object loadScore = metrics.get("loadScore");
                        return loadScore instanceof Double ? (Double) loadScore : 100.0;
                    })
                    .average()
                    .orElse(100.0);
            
            double avgCpuUsage = allMetrics.values().stream()
                    .mapToDouble(metrics -> {
                        Object cpu = metrics.get("cpuUsage");
                        return cpu instanceof Double ? (Double) cpu : 0.0;
                    })
                    .average()
                    .orElse(0.0);
            
            double avgMemoryUsage = allMetrics.values().stream()
                    .mapToDouble(metrics -> {
                        Object memory = metrics.get("memoryUsage");
                        return memory instanceof Double ? (Double) memory : 0.0;
                    })
                    .average()
                    .orElse(0.0);
            
            long totalRequests = allMetrics.values().stream()
                    .mapToLong(metrics -> {
                        Object requests = metrics.get("requestCount");
                        if (requests instanceof Number) {
                            return ((Number) requests).longValue();
                        }
                        return 0L;
                    })
                    .sum();
            
            // 부하 분산 효율성 평가
            String efficiency = avgLoadScore < 30 ? "EXCELLENT" :
                               avgLoadScore < 50 ? "GOOD" :
                               avgLoadScore < 70 ? "FAIR" : "POOR";
            
            performance.put("status", "UP");
            performance.put("instanceCount", allMetrics.size());
            performance.put("avgLoadScore", Math.round(avgLoadScore * 100.0) / 100.0);
            performance.put("avgCpuUsage", Math.round(avgCpuUsage * 100.0) / 100.0);
            performance.put("avgMemoryUsage", Math.round(avgMemoryUsage * 100.0) / 100.0);
            performance.put("totalRequests", totalRequests);
            performance.put("loadBalancingEfficiency", efficiency);
            performance.put("timestamp", LocalDateTime.now());
            
            return ResponseEntity.ok(performance);
            
        } catch (Exception e) {
            log.error("LoadBalancer 성능 정보 조회 실패", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("error", e.getMessage());
            error.put("timestamp", LocalDateTime.now());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Circuit Breaker 테스트용 엔드포인트
     */
    @GetMapping("/circuit")
    public Mono<ResponseDTO> fallback() {
        count++;
        if (count % 2 == 0) { // 50% 실패
            return Mono.error(new GeneralException(Code.SERVER_DOWN));
        }

        return Mono.just(ResponseDataDTO.of("success"));
    }

    /**
     * Spring Boot Actuator HealthIndicator 구현
     */
    @Override
    public Health health() {
        try {
            Map<String, Object> details = checkInternalHealth();
            
            // LoadBalancer 상태도 포함
            Map<String, Object> loadBalancerStatus = checkLoadBalancerHealth();
            details.put("loadBalancer", loadBalancerStatus);
            
            if ("UP".equals(details.get("status"))) {
                return Health.up().withDetails(details).build();
            } else {
                return Health.down().withDetails(details).build();
            }
        } catch (Exception e) {
            return Health.down().withException(e).build();
        }
    }

    /**
     * 내부 헬스체크 로직
     */
    private Map<String, Object> checkInternalHealth() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> details = new HashMap<>();
        
        try {
            // Memory 체크
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            
            Map<String, Object> memoryInfo = new HashMap<>();
            memoryInfo.put("max", maxMemory / (1024 * 1024) + "MB");
            memoryInfo.put("total", totalMemory / (1024 * 1024) + "MB");
            memoryInfo.put("used", usedMemory / (1024 * 1024) + "MB");
            memoryInfo.put("free", freeMemory / (1024 * 1024) + "MB");
            memoryInfo.put("usage", String.format("%.2f%%", (double) usedMemory / maxMemory * 100));
            
            details.put("memory", memoryInfo);

            result.put("status", "UP");
            result.put("details", details);
            result.put("timestamp", LocalDateTime.now());
            
        } catch (Exception e) {
            log.error("Internal health check failed", e);
            result.put("status", "DOWN");
            result.put("error", e.getMessage());
            result.put("timestamp", LocalDateTime.now());
        }
        
        return result;
    }

    /**
     * LoadBalancer 상태 체크
     */
    private Map<String, Object> checkLoadBalancerHealth() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            if (loadBalancerSupplier == null) {
                result.put("status", "DISABLED");
                result.put("message", "LoadBalancer가 비활성화 상태입니다.");
                result.put("timestamp", LocalDateTime.now());
                return result;
            }
            
            // 상세 상태 조회
            Map<String, Object> detailedStatus = loadBalancerSupplier.getDetailedStatus();
            
            // Number 클래스 활용으로 Integer/Long 모두 처리
            long totalInstances = ((Number) detailedStatus.getOrDefault("totalInstances", 0)).longValue();
            long healthyInstances = ((Number) detailedStatus.getOrDefault("healthyInstances", 0)).longValue();
            long metricsAvailableInstances = ((Number) detailedStatus.getOrDefault("metricsAvailableInstances", 0)).longValue();
            
            result.put("status", healthyInstances > 0 ? "UP" : "DOWN");
            result.put("totalInstances", totalInstances);
            result.put("healthyInstances", healthyInstances);
            result.put("metricsAvailableInstances", metricsAvailableInstances);
            
            // 현재 최적 인스턴스 정보
            Map<String, Object> bestInstance = (Map<String, Object>) detailedStatus.get("currentBestInstance");
            if (bestInstance != null) {
                result.put("currentBestInstance", bestInstance);
            }
            
            // LoadBalancer 효율성 평가
            if (healthyInstances > 0) {
                double healthyRatio = (double) healthyInstances / totalInstances * 100;
                String efficiency = healthyRatio >= 80 ? "EXCELLENT" :
                                  healthyRatio >= 60 ? "GOOD" :
                                  healthyRatio >= 40 ? "FAIR" : "POOR";
                result.put("healthRatio", Math.round(healthyRatio * 100.0) / 100.0);
                result.put("efficiency", efficiency);
            }
            
            result.put("timestamp", LocalDateTime.now());
            
        } catch (Exception e) {
            log.error("LoadBalancer 상태 체크 실패", e);
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
            result.put("timestamp", LocalDateTime.now());
        }
        
        return result;
    }

    /**
     * 외부 서비스 헬스체크
     */
    private Mono<Map<String, Object>> checkExternalServices() {
        Map<String, Object> result = new HashMap<>();
        
        Mono<Map<String, Object>> memberCheck = checkService("member", serverUrlMember + "/actuator/health");
        Mono<Map<String, Object>> batchCheck = checkService("batch", serverUrlServiceBatch + "/service/batch/health");
        Mono<Map<String, Object>> cocoinCheck = checkService("cocoin", serverUrlCocoin + "/actuator/health");

        return Mono.zip(memberCheck, batchCheck, cocoinCheck)
                .map(tuple -> {
                    result.put("member", tuple.getT1());
                    result.put("batch", tuple.getT2());
                    result.put("cocoin", tuple.getT3());
                    return result;
                });
    }

    /**
     * 개별 서비스 헬스체크
     */
    private Mono<Map<String, Object>> checkService(String serviceName, String url) {
        return webClientBuilder.build()
                .get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(java.time.Duration.ofSeconds(5))
                .map(response -> {
                    Map<String, Object> serviceHealth = new HashMap<>();
                    serviceHealth.put("status", "UP");
                    serviceHealth.put("response", response);
                    serviceHealth.put("url", url);
                    return serviceHealth;
                })
                .onErrorResume(throwable -> {
                    Map<String, Object> serviceHealth = new HashMap<>();
                    serviceHealth.put("status", "DOWN");
                    serviceHealth.put("error", throwable.getMessage());
                    serviceHealth.put("url", url);
                    return Mono.just(serviceHealth);
                });
    }

    /**
     * 전체 상태 결정 (LoadBalancer 상태도 고려)
     */
    private String determineOverallStatus(Map<String, Object> internalHealth, Map<String, Object> externalHealth) {
        if (!"UP".equals(internalHealth.get("status"))) {
            return "DOWN";
        }
        
        boolean allExternalUp = externalHealth.values().stream()
                .allMatch(status -> "UP".equals(((Map<?, ?>) status).get("status")));
        
        // LoadBalancer 상태도 고려
        Map<String, Object> loadBalancerHealth = checkLoadBalancerHealth();
        boolean loadBalancerUp = "UP".equals(loadBalancerHealth.get("status"));
        
        if (allExternalUp && loadBalancerUp) {
            return "UP";
        } else if (loadBalancerUp) {
            return "DEGRADED"; // 외부 서비스 일부 장애
        } else {
            return "DOWN"; // LoadBalancer 장애 시 전체 서비스 영향
        }
    }
}