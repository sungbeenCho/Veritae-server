package com.veritae.veritae_server.auth;

/**
 * 토큰 갱신 성공 시 발급되는 새 accessToken. refreshToken 은 회전하지 않으므로 포함하지 않는다(ADR-0001).
 */
public record RefreshedAccessToken(String accessToken, long expiresInSeconds) {
}
