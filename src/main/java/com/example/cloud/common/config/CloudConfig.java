package com.example.cloud.common.config;

//import com.example.cloud.common.config.jwt.provider.JwtTokenProvider;

import com.example.cloud.common.config.filter.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.function.Predicate;

@Configuration
@RequiredArgsConstructor
@ComponentScan(basePackages = {"org.example.core"})
public class CloudConfig {
    @Value("${server.url.member}")
    private String serverUrlMember;

    @Value("${server.url.ws}")
    private String serverUrlWs;

    @Value("${server.url.file}")
    private String serverUrlFile;

    @Value("${server.url.batch}")
    private String serverUrlBatch;

    @Value("${server.url.cocoin}")
    private String serverUrlCocoin;

    private final HeaderFilter headerFilter;
    private final PreLoggingFilter preLoggingFilter;
    private final AuthorizationTokenFilter authorizationTokenFilter;
    private final AuthorizationHeaderFilter authorizationHeaderFilter;
    private final PostLoggingFilter postLoggingFilter;

//    @Bean
//    public Customizer<Resilience4JCircuitBreakerFactory> circuitBreakerFactoryCustomizer() {
//        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
//                .failureRateThreshold(50)
//                .waitDurationInOpenState(Duration.ofMillis(1000))
//                .slidingWindowSize(2)
//                .build();
//        TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
//                .timeoutDuration(Duration.ofSeconds(4))
//                .build();
//
//        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
//                .circuitBreakerConfig(circuitBreakerConfig)
//                .timeLimiterConfig(timeLimiterConfig)
//                .build());
//    }

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCustomizer() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder("circuitBreaker")
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .failureRateThreshold(10)
                        .slowCallDurationThreshold(Duration.ofMillis(500))
                        .slidingWindowSize(20)
                        .minimumNumberOfCalls(10)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(5))
                        .build())
                .build());
    }

    @Bean
    public RouteLocator routeConfig(RouteLocatorBuilder builder) {
        return builder.routes()
                .route(r -> r.path("/test").filters(
                                        f -> f.circuitBreaker(c -> c.setRouteId("circuitBreaker"))
                                                .rewritePath("/.*/(?<param>.*)", "/delay/${param}")

                                )
                                .uri("lb://consumer")
                )
                .route(r -> r.path("/ws/**")
                        .uri(serverUrlWs)
                )
                .route(r -> r.path("/file/**")
                        .filters(
                                f -> f.setRequestHeader("Auth-header", "second")
                        )
                        .uri(serverUrlFile)
                )
                .route(r -> r.path("/batch/**")
                        .filters(
                                f -> f.setRequestHeader("Auth-header", "second")
                        )
                        .uri(serverUrlBatch)
                )
                .route(r -> r.path("/member/**")
                                .filters(
                                        f -> f.filter(preLoggingFilter.apply(new PreLoggingFilter.Config()))
                                                .filter(headerFilter.apply(new HeaderFilter.Config()))
                                                .filter(postLoggingFilter.apply(new PostLoggingFilter.Config()))
                                                .setRequestHeader("Auth-header", "second")
//                                                .circuitBreaker(c -> c.setName("circuitBreaker").setRouteId("circuitBreaker2"))
                                )
                                .uri(serverUrlMember)
                )
                .route(r -> r.path("/cocoin/ctf/**")
                                .filters(
                                        f -> f.filter(preLoggingFilter.apply(new PreLoggingFilter.Config()))
                                                .filter(headerFilter.apply(new HeaderFilter.Config()))
                                                .filter(postLoggingFilter.apply(new PostLoggingFilter.Config()))
                                                .setRequestHeader("Auth-header", "second")
                                )
                                .uri(serverUrlCocoin)
                )
                .route(r -> r.path("/cocoin/**")
                                .filters(
                                        f -> f.filter(preLoggingFilter.apply(new PreLoggingFilter.Config()))
//                                                .circuitBreaker(c -> c.setName("circuitBreaker"))
                                                .filter(headerFilter.apply(new HeaderFilter.Config()))
                                                .filter(authorizationTokenFilter.apply(new AuthorizationTokenFilter.Config()))
                                                .filter(authorizationHeaderFilter.apply(new AuthorizationHeaderFilter.Config()))
                                                .filter(postLoggingFilter.apply(new PostLoggingFilter.Config()))
                                                .setRequestHeader("Auth-header", "second")
                                )
                                .uri(serverUrlCocoin)
                )
                .build();
//        return route("common-route")
//                .route(
//                        GatewayRequestPredicates.path("/ws/"),
//                        http("ws://localhost:10270"))
//                .route(
//                        GatewayRequestPredicates.path("/file/**"),
//                        http("http://localhost:10280"))
//                .route(
//                        GatewayRequestPredicates.path("/batch/**"),
//                        http("http://localhost:10290"))
//                .filter(CircuitBreakerFilterFunctions.circuitBreaker("circuit"))
////                .filter(Bucket4jFilterFunctions.rateLimit(c -> c.setCapacity(5)
////                        .setPeriod(Duration.ofMinutes(1))
////                        .setKeyResolver(request -> request.remoteAddress().orElse(new InetSocketAddress("unknown", 0)).toString())
////                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS)
////                        .setHeaderName(HttpStatus.TOO_MANY_REQUESTS.name())
////                ))
//                .before(BeforeFilterFunctions.addRequestHeader("Auth-header", "second"))
//                .build()
//                .and(
//                        route("auth-route")
//                                .route(
//                                        GatewayRequestPredicates.path("/member/**"),
//                                        http("http://localhost:10260"))
//                                .route(
//                                        GatewayRequestPredicates.path("/cocoin"),
//                                        http("http://localhost:10190"))
//                                .filter(CircuitBreakerFilterFunctions.circuitBreaker("circuit"))
////                                .filter(Bucket4jFilterFunctions.rateLimit(c -> c.setCapacity(5)
////                                        .setPeriod(Duration.ofMinutes(1))
////                                        .setKeyResolver(request -> request.remoteAddress().orElse(new InetSocketAddress("unknown", 0)).toString())
////                                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS)
////                                        .setHeaderName(HttpStatus.TOO_MANY_REQUESTS.name())
////                                ))
//                                .filter(JWTFilterFunctions())
//                                .before(BeforeFilterFunctions.addRequestHeader("Auth-header", "second"))
//                                .build()
//                );
    }

//    private HandlerFilterFunction<ServerResponse, ServerResponse> JWTFilterFunctions() {
//        return (request, next) -> {
//            String jwt = request.headers().firstHeader("Authorization");
//            if (StringUtils.isBlank(jwt)) {
//                return next.handle(request);
//            }
//
//            try {
//                HttpServletRequest httpRequest = (HttpServletRequest) request;
//
//                String[] PERMIT_ALL_URL = {
//                        "/member/ctf/**",
//                        "/member/ctf/**",
//                        "/error"
//                };
//
//                if (Arrays.stream(PERMIT_ALL_URL).map(
//                        v -> v.replaceAll("/[*][*]", "")
//                ).anyMatch(
//                        v -> httpRequest.getServletPath().contains(v)
//                )) {
//                    ServerRequest newRequest = ServerRequest.from(request)
//                            .build();
//
//                    return next.handle(newRequest);
//                }
//
////                String accessToken = jwtTokenProvider.getHeaderToken(httpRequest);
////                if (accessToken != null && jwtTokenProvider.validateToken(accessToken)) {
//                    ServerRequest newRequest = ServerRequest.from(request)
//                            .build();
//
//                    return next.handle(newRequest);
////                } else {
////                    return ServerResponse.badRequest().build();
////                }
////                SignedJWT signedJWT = MyAppJwtUtils.parseJWT(jwt);
////                List<Map<String, String>> loginInfos = redisTemplate.opsForValue().get(signedJWT.getJWTClaimsSet().getClaim("email"));
////
////                assert loginInfos != null;
////                Map<String, String> loginMap = loginInfos.stream()
////                        .filter(map -> map.get("JSESSIONID").equals(request.headers().firstHeader("JSESSIONID")))
////                        .findFirst()
////                        .orElseThrow(AuthenticationException::new);
//
//
//            } catch (Exception e) {
//                return ServerResponse.badRequest().build();
//            }
//        };
//    }
}
