package com.veritae.veritae_server.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT access/refresh 토큰 발급 및 검증. stateless 이므로 서버 측에 토큰을 저장하지 않는다(ADR-0001).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private static final String CLAIM_TOKEN_TYPE = "tokenType";
    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String REFRESH_TOKEN_TYPE = "refresh";
    private static final int RANDOM_KEY_BYTES = 32;

    private final JwtProperties jwtProperties;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        String secret = jwtProperties.secret();
        if (secret == null || secret.isBlank()) {
            // JWT_SECRET 이 설정되지 않은 경우, 레포에 커밋된 고정 키를 절대 쓰지 않는다(누구나
            // 토큰을 위조할 수 있게 되는 landmine). 대신 프로세스마다 임의의 키를 생성해 사용한다 —
            // 재시작 시 기존 토큰이 전부 무효화되므로, 이 상태로 실서비스를 운영하면 사용자가 계속
            // 로그아웃되는 것으로 즉시 드러난다. 로컬 개발에서만 이 분기를 타야 한다.
            log.warn("app.jwt.secret(JWT_SECRET) 이 설정되지 않았습니다. 이번 프로세스에서만 유효한 "
                    + "임시 서명 키를 생성합니다 - 재시작하면 기존에 발급된 모든 토큰이 무효화됩니다. "
                    + "로컬 개발이 아니라면 즉시 JWT_SECRET 환경변수를 설정하세요.");
            byte[] randomKeyBytes = new byte[RANDOM_KEY_BYTES];
            new SecureRandom().nextBytes(randomKeyBytes);
            this.signingKey = Keys.hmacShaKeyFor(randomKeyBytes);
        } else {
            this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        }
    }

    public String generateAccessToken(UUID memberId) {
        return buildToken(memberId, ACCESS_TOKEN_TYPE, jwtProperties.accessTokenExpirySeconds());
    }

    public String generateRefreshToken(UUID memberId) {
        return buildToken(memberId, REFRESH_TOKEN_TYPE, jwtProperties.refreshTokenExpirySeconds());
    }

    public long getAccessTokenExpirySeconds() {
        return jwtProperties.accessTokenExpirySeconds();
    }

    /** 유효하고 만료되지 않은 access 토큰이면 subject(memberId) 를 반환한다. */
    public Optional<UUID> parseAndValidateAccessToken(String token) {
        return parseAndValidate(token, ACCESS_TOKEN_TYPE);
    }

    /** 유효하고 만료되지 않은 refresh 토큰이면 subject(memberId) 를 반환한다. */
    public Optional<UUID> parseAndValidateRefreshToken(String token) {
        return parseAndValidate(token, REFRESH_TOKEN_TYPE);
    }

    private String buildToken(UUID memberId, String tokenType, long expirySeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(memberId.toString())
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .signWith(signingKey)
                .compact();
    }

    private Optional<UUID> parseAndValidate(String token, String expectedTokenType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!expectedTokenType.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(claims.getSubject()));
            // JwtException: 만료/서명불일치/형식오류 등 모든 파싱 실패를 포괄한다.
            // IllegalArgumentException: subject 가 UUID 형식이 아니거나 token 이 비어있는 경우.
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
