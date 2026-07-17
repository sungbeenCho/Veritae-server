package com.veritae.veritae_server.auth;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 로그인 실패. 이메일 미존재/비밀번호 불일치를 구분하지 않는다(계정 열거 방지, ADR-0001).
 */
public class InvalidCredentialsException extends DomainException {

    public InvalidCredentialsException() {
        super("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED, "Invalid Credentials", "이메일 또는 비밀번호가 올바르지 않습니다.");
    }
}
