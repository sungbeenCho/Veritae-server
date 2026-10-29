package com.veritae.veritae_server.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.UUID;

/**
 * 인터넷에 열린 입구의 남용을 막는다: 로그인(비밀번호 대입), 가입(가짜 계정 대량 생성), 토큰 재발급은 IP마다,
 * 분석은 회원마다 센다(분석 한 건이 GPU를 약 2분 쓰므로 한 사람이 독차지하지 못하게). 조회 요청은 세지 않는다.
 * 운영에서는 Caddy 뒤에 있으므로 server.forward-headers-strategy=native로 실제 접속 IP를 getRemoteAddr()로 받는다.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Set<String> ANALYSIS_PATHS = Set.of(
            "/api/v1/analysis/image", "/api/v1/analysis/audio", "/api/v1/analysis/video");

    private final FixedWindowRateLimiter limiter;
    private final RateLimitProperties properties;

    public RateLimitInterceptor(FixedWindowRateLimiter limiter, RateLimitProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        String ip = request.getRemoteAddr();
        switch (path) {
            case "/api/v1/auth/login" -> check("login:" + ip, properties.login());
            case "/api/v1/auth/signup" -> check("signup:" + ip, properties.signup());
            case "/api/v1/auth/refresh" -> check("refresh:" + ip, properties.refresh());
            default -> {
                if (ANALYSIS_PATHS.contains(path)) {
                    check("analysis:" + analysisKey(ip), properties.analysis());
                }
            }
        }
        return true;
    }

    /** 분석은 인증이 필요한 요청이라 회원으로 센다. 인증 정보가 없으면(보안 필터가 먼저 막지만) IP로 센다. */
    private static String analysisKey(String ip) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UUID memberId) {
            return memberId.toString();
        }
        return ip;
    }

    private void check(String key, RateLimitProperties.Rule rule) {
        long retryAfter = limiter.tryAcquire(key, rule.maxRequests(), rule.window());
        if (retryAfter > 0) {
            throw new RateLimitExceededException(retryAfter);
        }
    }
}
