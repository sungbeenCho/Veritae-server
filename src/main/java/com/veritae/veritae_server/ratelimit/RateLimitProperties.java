package com.veritae.veritae_server.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 요청 횟수 제한(rate-limit.*). 로그인·가입·토큰 재발급은 IP마다, 분석(이미지·음성·영상 합산)은 회원마다 센다.
 */
@ConfigurationProperties("rate-limit")
public record RateLimitProperties(Rule login, Rule signup, Rule refresh, Rule analysis) {

    public record Rule(int maxRequests, Duration window) {
    }
}
