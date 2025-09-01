package com.example.cloud.common.instance;

import org.springframework.cloud.client.ServiceInstance;

public record WeightedInstance(ServiceInstance instance, double loadScore, double weight){};

