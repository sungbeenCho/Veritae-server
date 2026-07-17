package com.veritae.veritae_server.domain.member;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 회원가입 시 이미 가입된 이메일로 요청한 경우. Member 도메인 자체의 유일성 불변식 위반이다.
 */
public class EmailAlreadyExistsException extends DomainException {

    public EmailAlreadyExistsException() {
        super("EMAIL_ALREADY_EXISTS", HttpStatus.CONFLICT, "Email Already Exists", "이미 가입된 이메일입니다.");
    }
}
