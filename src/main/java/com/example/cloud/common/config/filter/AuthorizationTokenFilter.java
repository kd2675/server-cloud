package com.example.cloud.common.config.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.example.core.auth.UserContextHeaders;
import org.example.core.response.base.vo.Code;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AuthorizationTokenFilter extends AbstractGatewayFilterFactory<AuthorizationTokenFilter.Config> {
    @Value("${jwt.secret}")
    private String secret;

    @Override
    public GatewayFilter apply(AuthorizationTokenFilter.Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            String authorizationHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
                return OnError.onError(exchange, "No Authorization Header", Code.UNAUTHORIZED);
            }

            Claims claims = parseClaims(authorizationHeader.substring("Bearer ".length()));
            if (claims == null || claims.get("id") == null || claims.get("userEmail") == null) {
                return OnError.onError(exchange, "Token is not valid", Code.UNAUTHORIZED);
            }

            ServerHttpRequest userContextRequest = request.mutate()
                    .headers(headers -> {
                        removeUserContextHeaders(headers);
                        headers.set(UserContextHeaders.USER_ID, String.valueOf(claims.get("id")));
                        headers.set(UserContextHeaders.EMAIL, claims.get("userEmail", String.class));
                        headers.set(UserContextHeaders.ROLES, rolesHeaderValue(claims.get("roles")));
                    })
                    .build();

            return chain.filter(exchange.mutate().request(userContextRequest).build());
        };
    }

    private Claims parseClaims(String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            log.warn("exception is occurred : {}", e.getMessage());
            return null;
        }
    }

    private void removeUserContextHeaders(HttpHeaders headers) {
        new ArrayList<>(headers.keySet()).stream()
                .filter(header -> header.regionMatches(true, 0, "X-User-", 0, "X-User-".length()))
                .forEach(headers::remove);
    }

    private String rolesHeaderValue(Object roles) {
        if (roles instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
        }
        return roles == null ? "" : String.valueOf(roles);
    }

    @Getter
    @Setter
    public static class Config {
    }
}
