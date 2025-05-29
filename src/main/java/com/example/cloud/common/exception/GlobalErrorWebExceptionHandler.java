package com.example.cloud.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.example.core.response.base.exception.GeneralException;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.autoconfigure.web.reactive.error.AbstractErrorWebExceptionHandler;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.DefaultErrorAttributes;
import org.springframework.boot.web.reactive.error.ErrorAttributes;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
@Order(-2)
@Slf4j
public class GlobalErrorWebExceptionHandler extends AbstractErrorWebExceptionHandler {
    public GlobalErrorWebExceptionHandler(
            DefaultErrorAttributes g, ApplicationContext applicationContext,
            ServerCodecConfigurer serverCodecConfigurer) {
        super(g, new WebProperties.Resources(), applicationContext);
        super.setMessageWriters(serverCodecConfigurer.getWriters());
        super.setMessageReaders(serverCodecConfigurer.getReaders());
    }

    @Override
    protected RouterFunction<ServerResponse> getRoutingFunction(ErrorAttributes errorAttributes) {
        return RouterFunctions.route(RequestPredicates.all(), this::renderErrorResponse);
    }

    private Mono<ServerResponse> renderErrorResponse(ServerRequest request) {
        Map<String, Object> errorPropertiesMap = getErrorAttributes(request, ErrorAttributeOptions.defaults());
        Throwable error = getError(request);

        if (error instanceof GeneralException error1) {
            log.error(error.getMessage(), error);

            errorPropertiesMap.put("success", false);
            errorPropertiesMap.put("code", error1.getErrorCode().getCode());
            errorPropertiesMap.put("message", error1.getErrorCode().getMessage());

            return ServerResponse.status(error1.getErrorCode().getHttpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(errorPropertiesMap));
        } else if (error instanceof SpringCloudCircuitBreakerFilterFactory.CircuitBreakerStatusCodeException error2) {
            //fallback
            errorPropertiesMap.put("success", false);
            errorPropertiesMap.put("code", error2.getStatusCode().value());
            errorPropertiesMap.put("message", "통신 서버 에러");

            return ServerResponse.status(error2.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(errorPropertiesMap));
        } else {
            errorPropertiesMap.put("success", false);
            errorPropertiesMap.put("code", 503);
            errorPropertiesMap.put("message", "클라우드 서버와 통신 문제");
        }

        return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(errorPropertiesMap));
    }
}
