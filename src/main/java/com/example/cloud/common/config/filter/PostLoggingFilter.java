package com.example.cloud.common.config.filter;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

// PostLoggingFilter
@Slf4j
@Component
public class PostLoggingFilter extends AbstractGatewayFilterFactory<PostLoggingFilter.Config> {

    public PostLoggingFilter() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            return chain.filter(exchange).then(Mono.fromRunnable( () -> {
                ServerHttpResponse response = exchange.getResponse();
                log.info("response status : {} ", response.getStatusCode());
            }));
        };
    }

    @Getter
    @Setter
    public static class Config {
    }

}