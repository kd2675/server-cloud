package com.example.cloud.common.config.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.example.core.auth.UserContextHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationTokenFilterTest {
    private static final String SECRET = "testtesttesttesttsttesttesttesttest";

    @Test
    void setsUserContextHeadersFromJwtAndRemovesClientSuppliedUserHeaders() {
        AuthorizationTokenFilter filter = new AuthorizationTokenFilter();
        ReflectionTestUtils.setField(filter, "secret", SECRET);

        MockServerHttpRequest request = MockServerHttpRequest.post("/cocoin/ctf/order")
                .header("Authorization", "Bearer " + token())
                .header(UserContextHeaders.USER_ID, "999")
                .header("X-User-Injected", "spoofed")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> filteredExchange = new AtomicReference<>();
        GatewayFilterChain chain = next -> {
            filteredExchange.set(next);
            return Mono.empty();
        };

        filter.apply(new AuthorizationTokenFilter.Config())
                .filter(exchange, chain)
                .block();

        assertThat(filteredExchange.get().getRequest().getHeaders().getFirst(UserContextHeaders.USER_ID)).isEqualTo("7");
        assertThat(filteredExchange.get().getRequest().getHeaders().getFirst(UserContextHeaders.EMAIL)).isEqualTo("user@example.com");
        assertThat(filteredExchange.get().getRequest().getHeaders().getFirst(UserContextHeaders.ROLES)).isEqualTo("ROLE_USER,ROLE_ADMIN");
        assertThat(filteredExchange.get().getRequest().getHeaders().containsKey("X-User-Injected")).isFalse();
    }

    private String token() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.claims();
        claims.put("id", 7L);
        claims.put("userEmail", "user@example.com");
        claims.put("roles", List.of("ROLE_USER", "ROLE_ADMIN"));

        return Jwts.builder()
                .setClaims(claims)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }
}
