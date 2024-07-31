package com.example.cloud.service.auth.api.act;

import com.example.cloud.service.auth.api.biz.RefreshService;
import com.example.cloud.service.auth.api.dto.TokenDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.core.response.base.dto.ResponseDataDTO;
import org.example.core.response.base.exception.GeneralException;
import org.example.core.response.base.vo.Code;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(value = {"/api/auth"})
public class RefreshController {
    private final RefreshService refreshService;
    @PostMapping("/ctf/refresh")
    public ResponseDataDTO<TokenDTO> refresh(@CookieValue(name = "RefreshToken", required = false) String refreshToken) {
        if (refreshToken == null) {
            throw new GeneralException(Code.TOKEN_ILLEGAL_ARGUMENT);
        }
        TokenDTO tokenDTO = refreshService.refresh(refreshToken);
//        CookieUtils.createCookie("RefreshToken", JwtHeaderUtilEnums.GRANT_TYPE.getValue() + tokenDTO.getRefreshToken(), JwtExpirationEnums.REFRESH_TOKEN_EXPIRATION_TIME.getValue().intValue());
        return ResponseDataDTO.of(tokenDTO);
    }
}
