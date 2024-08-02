package com.example.cloud.common.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.gateway.server.mvc.filter.AfterFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.filter.Bucket4jFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;

@Configuration
public class CloudConfig {
    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> circuitBreakerFactoryCustomizer() {
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(1000))
                .slidingWindowSize(2)
                .build();
        TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(4))
                .build();

        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(circuitBreakerConfig)
                .timeLimiterConfig(timeLimiterConfig)
                .build());
    }

    @Bean
    public RouterFunction<ServerResponse> gatewayRouter() {
//        GatewayRequestPredicates.after(ZonedDateTime.parse("2017-01-20T17:42:47.789-07:00[America/Denver]"))
        return route("common-route")
                .route(
                        GatewayRequestPredicates.path("/ws/"),
                        HandlerFunctions.http("ws://localhost:20270"))
                .route(
                        GatewayRequestPredicates.path("/file/**"),
                        HandlerFunctions.http("http://localhost:20280"))
                .route(
                        GatewayRequestPredicates.path("/batch/**"),
                        HandlerFunctions.http("http://localhost:20290"))
                .filter(CircuitBreakerFilterFunctions.circuitBreaker("circuit"))
//                .filter(Bucket4jFilterFunctions.rateLimit(c -> c.setCapacity(5)
//                        .setPeriod(Duration.ofMinutes(1))
//                        .setKeyResolver(request -> request.remoteAddress().orElse(new InetSocketAddress("unknown", 0)).toString())
//                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS)
//                        .setHeaderName(HttpStatus.TOO_MANY_REQUESTS.name())
//                ))
                .before(BeforeFilterFunctions.addRequestHeader("Auth-header", "second"))
                .build()
                .and(
                        route("auth-route")
                                .route(
                                        GatewayRequestPredicates.path("/member/**"),
                                        HandlerFunctions.http("http://localhost:10260"))
                                .route(
                                        GatewayRequestPredicates.path("/cocoin"),
                                        HandlerFunctions.http("http://localhost:10190"))
                                .filter(CircuitBreakerFilterFunctions.circuitBreaker("circuit"))
//                                .filter(Bucket4jFilterFunctions.rateLimit(c -> c.setCapacity(5)
//                                        .setPeriod(Duration.ofMinutes(1))
//                                        .setKeyResolver(request -> request.remoteAddress().orElse(new InetSocketAddress("unknown", 0)).toString())
//                                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS)
//                                        .setHeaderName(HttpStatus.TOO_MANY_REQUESTS.name())
//                                ))
//                                .filter(JWTFilterFunctions())
                                .before(BeforeFilterFunctions.addRequestHeader("Auth-header", "second"))
                                .build()
                );


    }

    private HandlerFilterFunction<ServerResponse, ServerResponse> JWTFilterFunctions() {
        return (request, next) -> {
            String jwt = request.headers().firstHeader("MY_APP_TOKEN");
            if (StringUtils.isBlank(jwt)) {
                return next.handle(request);
            }

            try {
//                SignedJWT signedJWT = MyAppJwtUtils.parseJWT(jwt);
//                List<Map<String, String>> loginInfos = redisTemplate.opsForValue().get(signedJWT.getJWTClaimsSet().getClaim("email"));
//
//                assert loginInfos != null;
//                Map<String, String> loginMap = loginInfos.stream()
//                        .filter(map -> map.get("JSESSIONID").equals(request.headers().firstHeader("JSESSIONID")))
//                        .findFirst()
//                        .orElseThrow(AuthenticationException::new);

                ServerRequest newRequest = ServerRequest.from(request)
                        .build();

                return next.handle(newRequest);
            } catch (Exception e) {
                return ServerResponse.badRequest().build();
            }
        };
    }
}
