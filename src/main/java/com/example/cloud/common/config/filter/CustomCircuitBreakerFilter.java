//package com.example.cloud.common.config.filter;
//
//import org.springframework.cloud.gateway.filter.GatewayFilter;
//import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
//import org.springframework.http.HttpStatus;
//import org.springframework.stereotype.Component;
//import reactor.core.publisher.Mono;
//
//@Component
//public class CustomCircuitBreakerFilter extends AbstractGatewayFilterFactory<CustomCircuitBreakerFilter.Config> {
//
//    public CustomCircuitBreakerFilter() {
//        super(Config.class);
//    }
//
//    @Override
//    public GatewayFilter apply(Config config) {
//        return (exchange, chain) -> chain.filter(exchange)
//                .then(Mono.fromRunnable(() -> {
//                    HttpStatus statusCode = (HttpStatus) exchange.getResponse().getStatusCode();
//                    if (statusCode != null && !statusCode.equals(HttpStatus.OK)) {
//                        throw new RuntimeException("Non-200 status code: " + statusCode);
//                    }
//                }));
//    }
//
//    public static class Config {
//        // 필요한 설정이 있다면 여기에 추가
//    }
//}