package com.example.cloud.service.auth.api.act;

import com.example.cloud.service.auth.api.biz.LoginService;
import com.example.cloud.service.auth.api.dto.LoginParamDTO;
import com.example.cloud.service.auth.api.dto.TokenDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.core.response.base.dto.ResponseDataDTO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(value = {"/api/auth"})
public class LoginController {
    private final LoginService loginService;

    @PostMapping("/ctf/login")
    public ResponseDataDTO<TokenDTO> login(@RequestBody LoginParamDTO loginParamDTO) {
        return ResponseDataDTO.of(loginService.login(loginParamDTO));
    }
}
