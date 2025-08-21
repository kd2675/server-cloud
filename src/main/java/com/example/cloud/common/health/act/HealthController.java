package com.example.cloud.common.health.act;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.example.core.response.base.dto.ResponseDTO;
import org.example.core.response.base.dto.ResponseDataDTO;
import org.example.core.response.base.exception.GeneralException;
import org.example.core.response.base.vo.Code;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/server/cloud/health")
public class HealthController {
    private int count = 0;

    @GetMapping
    public ResponseDTO checkEndpoint() {
        return ResponseDTO.of(true, Code.OK);
    }

//    @GetMapping("/circuit")
//    public ResponseEntity<String> unstableEndpoint() {
//        count++;
//        if (count % 2 == 0) { // 50% 실패
//            throw new RuntimeException("Service failed!");
//        }
//        return ResponseEntity.ok("Request succeeded!");
//    }

    @GetMapping("/circuit")
    public Mono<ResponseDTO> fallback() {
        count++;
        if (count % 2 == 0) { // 50% 실패
            return Mono.error(new GeneralException(Code.SERVER_DOWN));
        }

        return Mono.just(ResponseDataDTO.of("success"));
    }
}
