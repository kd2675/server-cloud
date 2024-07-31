package com.example.cloud.service.auth.api.act;

import com.example.cloud.common.config.jwt.provider.JwtTokenProvider;
import com.example.cloud.service.auth.api.biz.LogoutService;
import com.example.cloud.service.auth.api.dto.TokenDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.core.response.base.dto.ResponseDTO;
import org.example.core.response.base.vo.Code;
import org.example.core.utils.CookieUtils;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(value = {"/api/auth"})
public class LogoutController {
    private final LogoutService logoutService;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/logout")
    public ResponseDTO logout(
            @RequestHeader("Authorization") String accessToken,
            @CookieValue("RefreshToken") String refreshToken
    ) {
        CookieUtils.deleteCookie("RefreshToken");
        String userEmail = jwtTokenProvider.getUserEmail(jwtTokenProvider.resolveToken(accessToken));
        logoutService.logout(userEmail, TokenDTO.of(accessToken, refreshToken));

        return ResponseDTO.of(true, Code.OK);
    }
}
