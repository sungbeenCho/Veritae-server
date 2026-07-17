package com.veritae.veritae_server.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Security 필터 체인은 DispatcherServlet 이전에 실행되므로 {@code @RestControllerAdvice} 가
 * 이 예외를 잡을 수 없다(spring-security-jwt 스킬 참고). 따라서 openapi.yaml 의 Unauthorized
 * 응답 스키마와 동일한 모양의 RFC 9457 본문을 여기서 직접 작성한다.
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String TYPE = "https://api.veritae.app/errors/unauthorized";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String body = """
                {
                  "type": "%s",
                  "title": "Unauthorized",
                  "status": 401,
                  "detail": "인증이 필요합니다.",
                  "instance": "%s",
                  "errorCode": "UNAUTHORIZED",
                  "timestamp": "%s"
                }
                """.formatted(TYPE, request.getRequestURI(), Instant.now());
        response.getWriter().write(body);
    }
}
