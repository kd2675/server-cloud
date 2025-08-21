package com.example.cloud.common.health.act;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.core.response.base.dto.ResponseDTO;
import org.example.core.response.base.dto.ResponseDataDTO;
import org.example.core.response.base.exception.GeneralException;
import org.example.core.response.base.vo.Code;
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
    
    @Value("${server.url.batch}")
    private String serverUrlBatch;
    
    @Value("${server.url.cocoin}")
    private String serverUrlCocoin;
    
    private final WebClient.Builder webClientBuilder;

    private int count = 0;

    /**
     * 기본 헬스체크 - Gateway 자체 상태
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("timestamp", LocalDateTime.now());
        status.put("service", "Cloud Gateway");
        
        return ResponseEntity.ok(status);
    }

    /**
     * 전체 시스템 헬스체크 (내부 + 외부 서비스)
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
                                    fullHealth.put("overall", determineOverallStatus(internalHealth, externalHealth));
                                    fullHealth.put("timestamp", LocalDateTime.now());
                                    return ResponseEntity.ok(fullHealth);
                                });
                    } else {
                        Map<String, Object> result = new HashMap<>();
                        result.put("gateway", internalHealth);
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
     * 외부 서비스 헬스체크
     */
    private Mono<Map<String, Object>> checkExternalServices() {
        Map<String, Object> result = new HashMap<>();
        
        Mono<Map<String, Object>> memberCheck = checkService("member", serverUrlMember + "/actuator/health");
        Mono<Map<String, Object>> batchCheck = checkService("batch", serverUrlBatch + "/service/batch/health");
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
                .bodyToMono(Map.class)
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
     * 전체 상태 결정
     */
    private String determineOverallStatus(Map<String, Object> internalHealth, Map<String, Object> externalHealth) {
        if (!"UP".equals(internalHealth.get("status"))) {
            return "DOWN";
        }
        
        boolean allExternalUp = externalHealth.values().stream()
                .allMatch(status -> "UP".equals(((Map<?, ?>) status).get("status")));
        
        return allExternalUp ? "UP" : "DEGRADED";
    }
}