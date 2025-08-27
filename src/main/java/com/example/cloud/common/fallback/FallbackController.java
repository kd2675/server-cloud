package com.example.cloud.common.fallback;

import lombok.extern.slf4j.Slf4j;
import org.example.core.response.base.dto.ResponseErrorDTO;
import org.example.core.response.base.exception.GeneralException;
import org.example.core.response.base.vo.Code;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/fallback")
public class FallbackController {
    @GetMapping
    public Mono<ResponseErrorDTO> fallback() {
        return Mono.error(new GeneralException(Code.SERVER_DOWN));
    }

    @RequestMapping("/service-batch")
    public ResponseEntity<Map<String, Object>> serviceBatchFallback() {
        log.warn("service-batch Circuit Breaker 작동 - Fallback 응답 반환");

        Map<String, Object> fallbackResponse = new HashMap<>();
        fallbackResponse.put("status", "CIRCUIT_BREAKER_OPEN");
        fallbackResponse.put("message", "service-batch 서비스가 일시적으로 이용 불가능합니다.");
        fallbackResponse.put("timestamp", System.currentTimeMillis());
        fallbackResponse.put("fallback", true);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(fallbackResponse);
    }

//    @RequestMapping("/service-batch")
//    public Mono<ResponseEntity<ResponseErrorDTO>> serviceBatchFallbackGet() {
//        log.warn("🔴 Service-Batch Circuit Breaker 발동 - GET 요청 Fallback 처리");
//
//        return Mono.error(new GeneralException(Code.SERVER_DOWN));
//    }

}
