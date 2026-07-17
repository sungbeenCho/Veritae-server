package com.veritae.veritae_server.auth;

/**
 * 로그인 성공 시 발급되는 토큰 쌍. API 계약(TokenResponse)과는 별개의 서비스 계층 산출물이며,
 * delegate/mapper 에서 생성된 DTO 로 변환한다.
 */
public record IssuedTokens(String accessToken, String refreshToken, long expiresInSeconds) {
}
