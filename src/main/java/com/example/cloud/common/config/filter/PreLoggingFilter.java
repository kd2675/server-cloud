package com.example.cloud.common.config.filter;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

// PreLoggingFilter
@Slf4j
@Component
public class PreLoggingFilter extends AbstractGatewayFilterFactory<PreLoggingFilter.Config> {

    public PreLoggingFilter() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            log.info("request information : {}, {}", request.getHeaders(), request.getURI());

            // reactive의 ServerHttpRequest
            ServerHttpRequest.Builder builder = exchange.getRequest().mutate();

            return chain.filter(exchange.mutate().request(builder.build()).build());
        };
    }

    @Getter
    @Setter
    public static class Config {
    }

}