package com.veritae.veritae_server.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;

/**
 * 모든 4xx/5xx 에러를 RFC 9457 {@link ProblemDetail} 공통 스키마(openapi.yaml 의
 * ProblemDetail/Violation 스키마)로 변환한다. 성공 응답은 raw DTO, 에러는 ProblemDetail 로
 * 통일하는 ADR-0001 의 결정을 따른다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String ERROR_BASE_URI = "https://api.veritae.app/errors/";

    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomainException(DomainException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getHttpStatus(), ex.getMessage());
        problem.setType(URI.create(ERROR_BASE_URI + toSlug(ex.getErrorCode())));
        problem.setTitle(ex.getTitle());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", ex.getErrorCode());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * SecurityFilterChain 이전 단계가 아니라(그건 ProblemDetailAuthenticationEntryPoint 가 처리),
     * 컨트롤러/서비스 레이어에서 방어적으로 AuthenticationException 이 던져지는 극히 드문 경로에 대한
     * 안전망. 정상 흐름에서는 도달하지 않는다.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
        problem.setType(URI.create(ERROR_BASE_URI + "unauthorized"));
        problem.setTitle("Unauthorized");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", "UNAUTHORIZED");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "요청 값 검증에 실패했습니다.");
        problem.setType(URI.create(ERROR_BASE_URI + "validation"));
        problem.setTitle("Validation Failed");
        problem.setProperty("errorCode", "VALIDATION_FAILED");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("violations", ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new Violation(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList());
        if (request instanceof ServletWebRequest servletWebRequest) {
            problem.setInstance(URI.create(servletWebRequest.getRequest().getRequestURI()));
        }
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}", request.getRequestURI(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "서버에서 예상하지 못한 오류가 발생했습니다.");
        problem.setType(URI.create(ERROR_BASE_URI + "internal-server-error"));
        problem.setTitle("Internal Server Error");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", "INTERNAL_SERVER_ERROR");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * ResponseEntityExceptionHandler 가 자체적으로 처리하는(우리가 @ExceptionHandler 로
     * 명시하지 않은) 모든 예외 — 잘못된 요청 바디, 405, 415, 404 등 — 도 최종적으로 이 훅을
     * 거쳐간다. 그 경로들이 만든 ProblemDetail 에 errorCode/timestamp 가 빠지지 않도록
     * 여기서 한 번 더 채워, 스펙이 모든 4xx/5xx 에 errorCode 를 필수로 요구하는 계약을
     * 빠짐없이 지킨다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey("errorCode")) {
                HttpStatus resolved = HttpStatus.resolve(statusCode.value());
                problem.setProperty("errorCode", resolved != null ? resolved.name() : "ERROR");
            }
            if (problem.getProperties() == null || !problem.getProperties().containsKey("timestamp")) {
                problem.setProperty("timestamp", Instant.now());
            }
            if (problem.getInstance() == null && request instanceof ServletWebRequest servletWebRequest) {
                problem.setInstance(URI.create(servletWebRequest.getRequest().getRequestURI()));
            }
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static String toSlug(String errorCode) {
        return errorCode.toLowerCase().replace('_', '-');
    }

    private record Violation(String field, String message) {
    }
}
