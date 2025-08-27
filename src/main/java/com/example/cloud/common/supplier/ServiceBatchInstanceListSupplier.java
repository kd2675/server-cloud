package com.example.cloud.common.supplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
//@Component
public class ServiceBatchInstanceListSupplier implements ServiceInstanceListSupplier {
    private final String serviceId = "service-batch";
    private final ConfigurableApplicationContext context;

    public ServiceBatchInstanceListSupplier(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public String getServiceId() {
        return serviceId;
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        // 정적 인스턴스 목록 정의
        List<ServiceInstance> instances = Arrays.asList(
                createServiceInstance("service-batch-1", "localhost", 20180),
                createServiceInstance("service-batch-2", "localhost", 20181),
                createServiceInstance("service-batch-3", "localhost", 20182)
        );

        return Flux.just(instances);
    }

    private ServiceInstance createServiceInstance(String instanceId, String host, int port) {
        return new ServiceInstance() {
            @Override
            public String getServiceId() {
                return serviceId;
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
                        "version", "1.0"
                );
            }
        };
    }
}