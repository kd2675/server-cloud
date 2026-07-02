package com.example.cloud.common.instance;

import org.springframework.cloud.client.ServiceInstance;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class LoadBalancedServiceBatchInstance implements ServiceInstance {
    public final String serviceId;
    public final String instanceId;
    public final String host;
    public final int port;
    public final boolean secure;
    private final URI uri;
    private final Map<String, String> metadata;
    public final AtomicBoolean isHealthy = new AtomicBoolean(true);

    public LoadBalancedServiceBatchInstance(String instanceId, String host, int port) {
        this("service-batch", instanceId, host, port);
    }

    public LoadBalancedServiceBatchInstance(String serviceId, String instanceId, String host, int port) {
        this.serviceId = serviceId;
        this.instanceId = instanceId;
        this.host = host;
        this.port = port;
        this.secure = false;
        this.uri = URI.create("http://" + host + ":" + port);
        this.metadata = Map.of();
    }

    public LoadBalancedServiceBatchInstance(ServiceInstance instance) {
        this.serviceId = instance.getServiceId();
        this.instanceId = instance.getInstanceId();
        this.host = instance.getHost();
        this.port = instance.getPort();
        this.secure = instance.isSecure();
        this.uri = instance.getUri();
        this.metadata = instance.getMetadata() == null ? Map.of() : new HashMap<>(instance.getMetadata());
    }

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
        return secure;
    }

    @Override
    public URI getUri() {
        return uri;
    }

    @Override
    public Map<String, String> getMetadata() {
        Map<String, String> result = new HashMap<>(metadata);
        result.putIfAbsent("zone", "default");
        result.put("healthy", String.valueOf(isHealthy.get()));
        result.put("loadBalanced", "true");
        result.put("metricsEnabled", "true");
        result.put("cacheType", "REDIS");
        result.put("strategy", "WEIGHTED");
        return result;
    }
}
