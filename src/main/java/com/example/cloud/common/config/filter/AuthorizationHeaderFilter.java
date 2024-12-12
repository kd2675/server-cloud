package com.example.cloud.common.config.filter;

import io.jsonwebtoken.Jwts;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.example.core.response.base.dto.ResponseDTO;
import org.example.core.response.base.vo.Code;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthorizationHeaderFilter extends AbstractGatewayFilterFactory<AuthorizationHeaderFilter.Config> {
    @Value("${jwt.secret}")
    private String secret;

    private final WebClient webClient;

    @Override
    public GatewayFilter apply(AuthorizationHeaderFilter.Config config) {
        return this::isTokenValid;
    }

    // 토큰 검증을 위한 메서드
    private Mono<Void> isTokenValid(ServerWebExchange exchange, GatewayFilterChain chain) {
        try {
            return webClient.get()
                    .uri("/cocoin/ctf/health")
                    .exchangeToMono(response -> (response.statusCode()
                            .is2xxSuccessful()) ? response.bodyToMono(ResponseDTO.class) : Mono.empty())
                    .doOnSuccess(
                            clientResponse -> {
                                log.error("{}", clientResponse);

                                if (!clientResponse.getCode().equals(1)) {
                                    throw new RuntimeException("Authorization filter failed");
                                }
                            }
                    )
                    .onErrorResume(error -> Mono.error(new RuntimeException(error.getMessage())))
//                    .map(Locale.LanguageRange::parse)
                    .map(range -> {
                        exchange.getRequest()
                                .mutate()
                                .headers(h -> h.setAcceptLanguage(Locale.LanguageRange.parse(Locale.getDefault().getLanguage())))
                                .build();

                        return exchange;
                    })
                    .flatMap(chain::filter);

        } catch (Exception e) {
            log.warn("exception is occurred : {}", e.getMessage());
            return Mono.error(Exception::new);
        }
    }

    // 토큰 검증을 위한 메서드
    private boolean isTokenValid(String token) {
        String subject = null;

        try {
            subject = Jwts.parser()
                    .setSigningKey(secret.getBytes())
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();

        } catch (Exception e) {
            log.warn("exception is occurred : {}", e.getMessage());
        }

        return !Strings.isBlank(subject);
    }

    @Getter
    @Setter
    public static class Config {
    }

}