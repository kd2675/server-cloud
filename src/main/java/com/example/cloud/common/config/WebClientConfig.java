package com.example.cloud.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {
//    @Bean
//    public WebClient webClient(WebClient.Builder builder) {
//        return builder.baseUrl("http://kimd0.iptime.org:10100")
//                .defaultHeaders(httpHeaders -> {
//                    httpHeaders.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
//                    httpHeaders.set("Auth-header", "second");
//                })
//                .build();
//    }
}
