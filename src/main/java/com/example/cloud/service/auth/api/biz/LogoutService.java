package com.example.cloud.service.auth.api.biz;

import com.example.cloud.common.config.jwt.cache.CacheKey;
import com.example.cloud.common.config.jwt.provider.JwtTokenProvider;
import com.example.cloud.service.auth.api.dto.TokenDTO;
import com.example.cloud.service.auth.database.rep.redis.logout.LogoutAccessTokenRedisREP;
import com.example.cloud.service.auth.database.rep.redis.refresh.RefreshTokenRedisREP;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.database.auth.database.rep.redis.logout.LogoutAccessTokenRedis;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogoutService {
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRedisREP refreshTokenRedisREP;
    private final LogoutAccessTokenRedisREP logoutAccessTokenRedisREP;

    // 4
    @Transactional
    @CacheEvict(value = CacheKey.USER, key = "#p0")
    public void logout(String email, TokenDTO tokenDTO) {
        String accessToken = jwtTokenProvider.resolveToken(tokenDTO.getAccessToken());
        String refreshToken = jwtTokenProvider.resolveToken(tokenDTO.getRefreshToken());
        long remainMilliSeconds = jwtTokenProvider.getRemainMilliSeconds(refreshToken);
        refreshTokenRedisREP.deleteById(email);
        logoutAccessTokenRedisREP.save(LogoutAccessTokenRedis.of(accessToken, email, remainMilliSeconds));
    }

}
