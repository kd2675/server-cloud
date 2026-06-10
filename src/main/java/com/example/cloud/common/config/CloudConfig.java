package com.example.cloud.common.config;

//import com.example.cloud.common.config.jwt.provider.JwtTokenProvider;

import com.example.cloud.common.config.filter.*;
import lombok.RequiredArgsConstructor;
import org.example.core.utils.ServerTypeUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.Set;

@Configuration
@RequiredArgsConstructor
@ComponentScan(basePackages = {"org.example.core"})
public class CloudConfig {
    private static final String AUTH_HEADER = "Auth-header";
    private static final String CLOUD_AUTH_HEADER_VALUE = "cloud";

    @Value("${path.server.member.url}")
    private String serverUrlMember;

    @Value("${path.server.socket.url}")
    private String serverUrlSocket;

    @Value("${path.server.file.url}")
    private String serverUrlFile;

    @Value("${path.server.cloud.url}")
    private String serverUrlCloud;

    @Value("${path.server.batch.url}")
    private String serverUrlBatch;

    @Value("${path.service.cocoin.url}")
    private String serverUrlCocoin;

    @Value("${path.service.batch.url}")
    private String serverUrlServiceBatch;

    // LoadBalancer용 URI 추가
    private static final String LB_SERVICE_BATCH = "lb://service-batch";
    private static final String LB_SERVER_MEMBER = "lb://server-member";
    private static final String LB_SERVER_FILE = "lb://server-file";
    private static final String LB_SERVICE_COCOIN = "lb://service-cocoin";
    private static final String LB_SERVER_BATCH = "lb://server-batch";

    private static final String LB_SERVICE_BATCH_BACKUP = "lb://service-batch-backup";



    private final HeaderFilter headerFilter;
    private final PreLoggingFilter preLoggingFilter;
    private final AuthorizationTokenFilter authorizationTokenFilter;
    private final AuthorizationHeaderFilter authorizationHeaderFilter;
    private final PostLoggingFilter postLoggingFilter;

    @Bean
    @Order(-100)
    public WebFilter gatewayAuthFilter() {
        return (ServerWebExchange ctx, WebFilterChain chain) -> {
            ServerHttpRequest request = ctx.getRequest();
            String path = request.getPath().pathWithinApplication().value();

            if (CorsUtils.isPreFlightRequest(request) || isGatewayAuthExemptPath(path)) {
                return chain.filter(ctx);
            }

            String authHeader = request.getHeaders().getFirst(AUTH_HEADER);
            if (!CLOUD_AUTH_HEADER_VALUE.equals(authHeader)) {
                ServerHttpResponse response = ctx.getResponse();
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return response.setComplete();
            }

            return chain.filter(ctx);
        };
    }

    private boolean isGatewayAuthExemptPath(String path) {
        return path.equals("/actuator/health")
                || path.startsWith("/actuator/health/")
                || path.equals("/actuator/info")
                || path.startsWith("/service/batch/webhook/");
    }

    @Bean
    public WebFilter corsFilter() {
        return (ServerWebExchange ctx, WebFilterChain chain) -> {
            ServerHttpRequest request = ctx.getRequest();

            String origins = null;
            if (ServerTypeUtils.isLocal()) {
                origins = "http://localhost:20090";
            } else {
                origins = "http://localhost";
            }

            if (CorsUtils.isPreFlightRequest(request)) {
                ServerHttpResponse response = ctx.getResponse();
                HttpHeaders headers = response.getHeaders();
                headers.add("Access-Control-Allow-Origin", origins);
                headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                headers.add("Access-Control-Allow-Headers", "authorization ,X-Auth-Token, X-Requested-With, Content-Type, Original, " + AUTH_HEADER);
                headers.add("Access-Control-Allow-Credentials", "true");

                if (request.getMethod() == HttpMethod.OPTIONS) {
                    response.setStatusCode(HttpStatus.OK);
                    return Mono.empty();
                }
            }
            return chain.filter(ctx);
        };
    }

    @Bean
    public RouteLocator routeConfig(RouteLocatorBuilder builder) {
        Set<String> fallbackStatusCodes = new HashSet<>();
        for (int i = 400; i < 600; i++) {
            fallbackStatusCodes.add(String.valueOf(i));
        }

        return builder.routes()
                .route("health", r -> r.path("/test/circuit")
                        .filters(
                                f -> f.setPath("/server/cloud/health/circuit")
                                        .setRequestHeader(AUTH_HEADER, CLOUD_AUTH_HEADER_VALUE)
                                        .circuitBreaker(
                                                c -> c.setName("myCircuitBreaker")
                                                        .setStatusCodes(fallbackStatusCodes)
                                        )
                                        .filter(preLoggingFilter.apply(new PreLoggingFilter.Config()))
                                        .filter(headerFilter.apply(new HeaderFilter.Config()))
                                        .filter(postLoggingFilter.apply(new PostLoggingFilter.Config()))
                        )
                        .uri(serverUrlCloud)
                ).route(r -> r.path("/file/**")
                        .filters(
                                f -> f.setRequestHeader(AUTH_HEADER, "second")
                        )
                        .uri(serverUrlFile)
                ).route(r -> r.path("/batch/**")
                        .filters(
                                f -> f.setRequestHeader(AUTH_HEADER, "second")
                        )
                        .uri(serverUrlBatch)
                ).route(r -> r.path("/member/**")
                        .filters(
                                f -> f.filter(preLoggingFilter.apply(new PreLoggingFilter.Config()))
                                        .filter(headerFilter.apply(new HeaderFilter.Config()))
                                        .filter(postLoggingFilter.apply(new PostLoggingFilter.Config()))
                                        .setRequestHeader(AUTH_HEADER, "second")
//                                                .circuitBreaker(c -> c.setName("circuitBreaker"))
                        )
                        .uri(serverUrlMember)
                ).route(r -> r.path("/cocoin/ctf/**")
                        .filters(
                                f -> f.filter(preLoggingFilter.apply(new PreLoggingFilter.Config()))
                                        .filter(headerFilter.apply(new HeaderFilter.Config()))
                                        .filter(postLoggingFilter.apply(new PostLoggingFilter.Config()))
                                        .circuitBreaker(
                                                c -> c.setName("myCircuitBreaker")
                                                        .setStatusCodes(fallbackStatusCodes)
                                        )
                                        .setRequestHeader(AUTH_HEADER, "second")
                        )
                        .uri(serverUrlCocoin)
                ).route(r -> r.path("/cocoin/**")
                        .filters(
                                f -> f.filter(preLoggingFilter.apply(new PreLoggingFilter.Config()))
                                        .filter(headerFilter.apply(new HeaderFilter.Config()))
                                        .filter(authorizationTokenFilter.apply(new AuthorizationTokenFilter.Config()))
                                        .filter(authorizationHeaderFilter.apply(new AuthorizationHeaderFilter.Config()))
                                        .filter(postLoggingFilter.apply(new PostLoggingFilter.Config()))
                                        .circuitBreaker(
                                                c -> c.setName("myCircuitBreaker")
                                                        .setStatusCodes(fallbackStatusCodes)
                                        )
                                        .setRequestHeader(AUTH_HEADER, "second")
                        )
                        .uri(serverUrlCocoin)
                )
//                .route(r -> r.path("/service/batch/**")
//                        .filters(
//                                f -> f.filter(preLoggingFilter.apply(new PreLoggingFilter.Config()))
//                                        .filter(postLoggingFilter.apply(new PostLoggingFilter.Config()))
//                                        .setRequestHeader("Auth-header", "second")
//                        )
//                        .uri(serverUrlServiceBatch)
//                )
                .route("service-batch-loadbalanced", r -> r.path("/service/batch/**")
                        .filters(f -> f
                                .filter(preLoggingFilter.apply(new PreLoggingFilter.Config()))
                                .filter(postLoggingFilter.apply(new PostLoggingFilter.Config()))
                                .setRequestHeader(AUTH_HEADER, "second")
                                .circuitBreaker(c -> c
                                        .setName("serviceBatchCircuitBreaker")
                                        .setStatusCodes(fallbackStatusCodes)
                                        .setFallbackUri("forward:/fallback/service-batch")
                                )
                                .retry(retryConfig -> retryConfig
                                        .setRetries(2)
                                        .setMethods(HttpMethod.GET, HttpMethod.POST)
                                        .setSeries(HttpStatus.Series.SERVER_ERROR)
                                )
                        )
                        .uri(LB_SERVICE_BATCH) // 로드밸런서 URI 사용
                )
                // 백업 라우트: fallback 진입 시 동일 경로를 백업 클러스터로 전달
                .route("service-batch-backup-loadbalanced", r -> r.path("/fallback/service-batch/**")
                        .filters(f -> f
                                .rewritePath("/fallback/service-batch(?<segment>/?.*)", "/service/batch${segment}") // 원래 서비스 경로로 정규화
                                .setRequestHeader("X-Fallback-Routed", "true")
                                .setRequestHeader(AUTH_HEADER, "second")
                                .filter(preLoggingFilter.apply(new PreLoggingFilter.Config()))
                                .filter(postLoggingFilter.apply(new PostLoggingFilter.Config()))
                        )
                        .uri(LB_SERVICE_BATCH_BACKUP)
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
