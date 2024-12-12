package com.example.cloud.common.config.filter;

import lombok.extern.slf4j.Slf4j;
import org.example.core.response.base.exception.GeneralException;
import org.example.core.response.base.vo.Code;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
public class OnError {
    public static Mono<Void> onError(ServerWebExchange exchange, String error, HttpStatus httpStatus) {
        ServerHttpResponse response = exchange.getResponse();

        response.setStatusCode(httpStatus);

        log.warn("OnError is occurred : {}", error);

        return Mono.error(Exception::new);
    }

    public static Mono<Void> onError(ServerWebExchange exchange, String error, Code code) {
        ServerHttpResponse response = exchange.getResponse();

        response.setStatusCode(code.getHttpStatus());

        log.warn("OnError is occurred : {}", error);

        return Mono.error(new GeneralException(code));
    }
}
