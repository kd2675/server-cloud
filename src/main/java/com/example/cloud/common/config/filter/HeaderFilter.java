package com.example.cloud.common.config.filter;

import io.jsonwebtoken.Jwts;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class HeaderFilter extends AbstractGatewayFilterFactory<HeaderFilter.Config> {

    @Value("${jwt.secret}")
    private String secret;

    @Override
    public GatewayFilter apply(HeaderFilter.Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            if (!request.getHeaders().containsKey("Auth-header")) {
                return OnError.onError(exchange, "No Header", HttpStatus.UNAUTHORIZED);
            }

            String authorizationHeader = request.getHeaders().get("Auth-header").get(0);

            if (!StringUtils.equals(authorizationHeader, "cloud")) {
                return OnError.onError(exchange, "Not Match Header", HttpStatus.UNAUTHORIZED);
            }

            return chain.filter(exchange);
        };
    }

    @Getter
    @Setter
    public static class Config {}

}