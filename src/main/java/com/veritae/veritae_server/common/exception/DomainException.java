package com.veritae.veritae_server.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 도메인 규칙 위반을 나타내는 모든 예외의 베이스. RFC 9457 ProblemDetail 매핑에 필요한
 * errorCode/httpStatus/title 을 예외 스스로 들고 있어, GlobalExceptionHandler 는
 * 이 타입 하나만 처리하면 된다.
 */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;
    private final String title;

    protected DomainException(String errorCode, HttpStatus httpStatus, String title, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.title = title;
    }

    protected DomainException(String errorCode, HttpStatus httpStatus, String title, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.title = title;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getTitle() {
        return title;
    }
}
