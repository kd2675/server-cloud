package com.example.cloud.common.config.jwt.provider;

import com.example.cloud.common.config.jwt.enums.JwtExpirationEnums;
import com.example.cloud.common.config.jwt.enums.JwtHeaderUtilEnums;
import com.example.cloud.service.auth.database.rep.redis.logout.LogoutAccessTokenRedisREP;
import com.example.cloud.service.auth.database.rep.redis.refresh.RefreshTokenRedisREP;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.core.response.base.exception.GeneralException;
import org.example.core.response.base.vo.Code;
import org.example.core.utils.CookieUtils;
import org.example.database.auth.database.rep.redis.refresh.RefreshTokenRedis;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.Optional;
import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    private final RefreshTokenRedisREP refreshTokenRedisREP;
    private final LogoutAccessTokenRedisREP logoutAccessTokenRedisREP;

    @Value("${jwt.secret}")
    private String SECRET_KEY;
    public static final String AUTHORIZATION_HEADER = "Authorization";

    public String generateAccessToken(String userEmail) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

        String token =
                doGenerateToken(
                        userDetails.getUsername(),
                        JwtExpirationEnums.ACCESS_TOKEN_EXPIRATION_TIME.getValue()
                );

        return token;
    }

    public String generateRefreshToken(String userEmail) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

        String token =
                doGenerateToken(
                        userDetails.getUsername(),
                        JwtExpirationEnums.REFRESH_TOKEN_EXPIRATION_TIME.getValue()
                );

        refreshTokenRedisREP.save(
                RefreshTokenRedis.createRefreshToken(
                        userEmail,
                        token,
                        JwtExpirationEnums.REFRESH_TOKEN_EXPIRATION_TIME.getValue()
                )
        );

        CookieUtils.createCookie("RefreshToken", JwtHeaderUtilEnums.GRANT_TYPE.getValue() + token, JwtExpirationEnums.REFRESH_TOKEN_EXPIRATION_TIME.getValue().intValue());

        return token;
    }
    private String doGenerateToken(String userEmail, long expireTime) { // 1
        Claims claims = Jwts.claims();
        claims.put("userEmail", userEmail);

        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expireTime))
                .signWith(getSigningKey(SECRET_KEY), SignatureAlgorithm.HS256)
                .compact();
    }

//    ------------------------------------------------------------------------------------------------------------------

    public String resolveToken(String token) {
        return token.substring(7);
    }

    public String getHeaderToken(HttpServletRequest request) {
        String headerAuth = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith(JwtHeaderUtilEnums.GRANT_TYPE.getValue())) {
            return resolveToken(headerAuth);
        }
        return null;
    }

    public Claims extractAllClaims(String token) { // 2
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey(SECRET_KEY))
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String getUserEmail() {
        Authentication authentication = Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .orElseThrow(() -> new GeneralException(Code.NO_SEARCH_USER, "회원이 없습니다."));
        try {
            UserDetails principal = (UserDetails) authentication.getPrincipal();
            return principal.getUsername();
        } catch (ClassCastException e) {
            throw new GeneralException(Code.BAD_REQUEST);
//            throw new ClassCastException();
        }
    }

    // 토큰에서 회원 정보 추출
    public String getUserEmail(String token) {
        return extractClaim(token, v -> v.get("userEmail", String.class));
    }

    public Boolean validateToken(String accessToken) {
        //로그아웃 체크
        if (logoutAccessTokenRedisREP.existsById(accessToken)) {
            return false;
        }

        extractAllClaims(accessToken);

        return true;
    }

    // JWT 에서 인증 정보 조회
    public Authentication getAuthentication(String token) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(this.getUserEmail(token));
        return new UsernamePasswordAuthenticationToken(userDetails, "", userDetails.getAuthorities());
    }

    private Key getSigningKey(String secretKey) {
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        Key secretKey1 = Keys.hmacShaKeyFor(keyBytes);
        return secretKey1;
    }

    public void checkPassword(String rawPassword, String findMemberPassword) {
        if (!passwordEncoder.matches(rawPassword, findMemberPassword)) {
            throw new GeneralException(Code.NOT_MATCH_PASSWORD, "비밀번호가 맞지 않습니다.");
        }
    }

    public long getRemainMilliSeconds(String token) {
        Date expiration = extractAllClaims(token).getExpiration();
        Date now = new Date();
        return expiration.getTime() - now.getTime();
    }

}
