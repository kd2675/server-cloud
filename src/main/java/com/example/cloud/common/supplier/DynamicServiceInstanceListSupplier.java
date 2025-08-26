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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DynamicServiceInstanceListSupplier implements ServiceInstanceListSupplier {

    private final String serviceId = "service-batch";
    private final WebClient webClient;
    private final List<ServiceBatchInstance> staticInstances;

    public DynamicServiceInstanceListSupplier(ConfigurableApplicationContext context) {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();

        // 정적 인스턴스 정의
        this.staticInstances = Arrays.asList(
                new ServiceBatchInstance("service-batch-1", "localhost", 20180),
                new ServiceBatchInstance("service-batch-2", "localhost", 20181),
                new ServiceBatchInstance("service-batch-3", "localhost", 20182)
        );

        // 주기적 헬스체크 시작
        startHealthCheck();
    }

    @Override
    public String getServiceId() {
        return serviceId;
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        // 현재 활성화된 인스턴스만 반환
        List<ServiceInstance> activeInstances = staticInstances.stream()
                .filter(instance -> instance.isHealthy.get())
                .collect(Collectors.toList());

        if (activeInstances.isEmpty()) {
            log.warn("사용 가능한 service-batch 인스턴스가 없습니다. 모든 인스턴스를 반환합니다.");
            return Flux.just(staticInstances.stream().collect(Collectors.toList()));
        }

        log.info("활성화된 인스턴스 수: {}/{}", activeInstances.size(), staticInstances.size());
        return Flux.just(activeInstances);
    }

    private void startHealthCheck() {
        Flux.interval(Duration.ofSeconds(30))
                .doOnNext(tick -> performHealthCheck())
                .subscribe();
    }

    private void performHealthCheck() {
        staticInstances.forEach(this::checkInstanceHealth);
    }

    private void checkInstanceHealth(ServiceBatchInstance instance) {
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
                                log.info("인스턴스 {}:{} 상태 변경: {} -> {}",
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

    private static class ServiceBatchInstance implements ServiceInstance {
        private final String instanceId;
        private final String host;
        private final int port;
        private final AtomicBoolean isHealthy = new AtomicBoolean(true);

        public ServiceBatchInstance(String instanceId, String host, int port) {
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
                    "healthy", String.valueOf(isHealthy.get())
            );
        }
    }
}
