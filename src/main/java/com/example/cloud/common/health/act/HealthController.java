package com.example.cloud.common.health.act;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.core.response.base.dto.ResponseDataDTO;
import org.example.log.annotation.Log;
import org.example.log.annotation.Timer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class HealthController {

    @GetMapping("/ctf/health")
    @Log
    @Timer
    public ResponseDataDTO<String> health() {
        return ResponseDataDTO.of("OK");
    }

}
