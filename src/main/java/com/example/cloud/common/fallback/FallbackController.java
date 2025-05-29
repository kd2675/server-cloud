package com.example.cloud.common.fallback;

import org.example.core.response.base.dto.ResponseErrorDTO;
import org.example.core.response.base.exception.GeneralException;
import org.example.core.response.base.vo.Code;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class FallbackController {
    @GetMapping("/fallback")
    public Mono<ResponseErrorDTO> fallback() {
        return Mono.error(new GeneralException(Code.SERVER_DOWN));
    }
}
