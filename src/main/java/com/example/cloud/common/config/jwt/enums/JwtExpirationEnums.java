package com.example.cloud.common.config.jwt.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum JwtExpirationEnums {
//    ACCESS_TOKEN_EXPIRATION_TIME("JWT 만료 시간 / 3시간", 1000L * 60 * 60 * 3),
//    REFRESH_EXPIRATION_TIME("Refresh 토큰 재발급 시간 / 3일", 1000L * 60 * 60 * 24 * 3),
//    REFRESH_TOKEN_EXPIRATION_TIME("Refresh 토큰 만료 시간 / 7일", 1000L * 60 * 60 * 24 * 3);

//    ACCESS_TOKEN_EXPIRATION_TIME("JWT 만료 시간 / 120초", 1000L * 120),
//    ACCESS_TOKEN_EXPIRATION_TIME("JWT 만료 시간 / 30분", 1000L * 60 * 30),

    ACCESS_TOKEN_EXPIRATION_TIME("JWT 만료 시간 / 7일", 1000L * 60 * 60 * 60 * 7),
    REFRESH_EXPIRATION_TIME("Refresh 토큰 재발급 시간 / 10일", 1000L * 60 * 60 * 24 * 10),
    REFRESH_TOKEN_EXPIRATION_TIME("Refresh 토큰 만료 시간 / 15일", 1000L * 60 * 60 * 24 * 15);

    private String description;
    private Long value;
}
