package com.example.cloud.common.health.act;

import com.example.cloud.common.supplier.EurekaBasedServiceInstanceListSupplier;
import com.example.cloud.common.supplier.ExtendedServiceInstanceListSupplier;
import com.example.cloud.common.supplier.WeightedMetricsBasedRedisServiceInstanceListSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/server/cloud/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController implements HealthIndicator {
    // LoadBalancer 상태 조회를 위한 Supplier 주입

    private final ExtendedServiceInstanceListSupplier loadBalancerSupplier;

    /**
     * Spring Boot Actuator HealthIndicator 구현
     */
    @Override
    public Health health() {
        try {
            Map<String, Object> loadBalancerStatus = checkLoadBalancerHealth();
            Map<String, Object> details = checkInternalHealth();

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
}