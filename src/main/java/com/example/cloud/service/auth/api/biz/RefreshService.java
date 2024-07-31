package com.example.cloud.service.auth.api.biz;

import com.example.cloud.common.config.jwt.enums.JwtExpirationEnums;
import com.example.cloud.common.config.jwt.provider.JwtTokenProvider;
import com.example.cloud.service.auth.api.dto.TokenDTO;
import com.example.cloud.service.auth.database.rep.redis.refresh.RefreshTokenRedisREP;
import lombok.RequiredArgsConstructor;
import org.example.core.response.base.exception.GeneralException;
import org.example.core.response.base.vo.Code;
import org.example.core.utils.CookieUtils;
import org.example.database.auth.database.rep.redis.refresh.RefreshTokenRedis;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefreshService {
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRedisREP refreshTokenRedisREP;
    @Transactional(readOnly = true)
    public TokenDTO refresh(String refreshToken) {
        refreshToken = jwtTokenProvider.resolveToken(refreshToken);
        String userEmail = jwtTokenProvider.getUserEmail(refreshToken);
        RefreshTokenRedis redisRefreshToken = refreshTokenRedisREP.findById(userEmail)
                .orElseThrow(() -> new GeneralException(Code.NO_SEARCH_USER));
        if (!refreshToken.equals(redisRefreshToken.getRefreshToken())) {
            CookieUtils.deleteCookie("RefreshToken");
            throw new GeneralException(Code.TOKEN_ILLEGAL_ARGUMENT);
        }

        TokenDTO tokenDTO = refreshRefreshToken(refreshToken, userEmail);
//        CookieUtils.createCookie("RefreshToken", JwtHeaderUtilEnums.GRANT_TYPE.getValue() + tokenDTO.getRefreshToken(), JwtExpirationEnums.REFRESH_TOKEN_EXPIRATION_TIME.getValue().intValue());

        return tokenDTO;
    }

    private TokenDTO refreshRefreshToken(String refreshToken, String userEmail) {
        if (lessThanRefreshExpirationTimesLeft(refreshToken)) {
            return TokenDTO.of(jwtTokenProvider.generateAccessToken(userEmail), jwtTokenProvider.generateRefreshToken(userEmail));
        }
        return TokenDTO.of(jwtTokenProvider.generateAccessToken(userEmail), refreshToken);
    }

    private boolean lessThanRefreshExpirationTimesLeft(String refreshToken) {
        return jwtTokenProvider.getRemainMilliSeconds(refreshToken) < JwtExpirationEnums.REFRESH_EXPIRATION_TIME.getValue();
    }
}
