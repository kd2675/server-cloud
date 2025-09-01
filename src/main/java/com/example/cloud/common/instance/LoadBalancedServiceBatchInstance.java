package com.example.cloud.common.instance;

import org.springframework.cloud.client.ServiceInstance;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class LoadBalancedServiceBatchInstance implements ServiceInstance {
    public final String instanceId;
    public final String host;
    public final int port;
    public final AtomicBoolean isHealthy = new AtomicBoolean(true);

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
                "cacheType", "REDIS",
                "strategy", "WEIGHTED"
        );
    }
}
