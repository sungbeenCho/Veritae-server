package com.veritae.veritae_server.auth.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.jwt.* 설정. secret 은 JWT_SECRET 환경변수로 주입한다(코드/설정파일에 고정값 하드코딩 금지).
 * 비어있으면 {@link JwtTokenProvider} 가 프로세스별 임시 키를 생성해 대체한다 - 로컬 개발에서만
 * 이 경로를 타야 하며, 실서비스에서는 반드시 JWT_SECRET 을 설정해야 한다.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, long accessTokenExpirySeconds, long refreshTokenExpirySeconds) {
}
