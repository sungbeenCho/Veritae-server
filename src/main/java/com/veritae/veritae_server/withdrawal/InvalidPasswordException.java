package com.veritae.veritae_server.withdrawal;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 탈퇴 시 재확인한 비밀번호가 틀림. 401 이 아니라 400 인 이유: 401 은 앱이 "토큰이 만료됐다"로 받아들여
 * 토큰 갱신/재로그인 흐름으로 빠질 수 있다 - 여기는 로그인은 유효하고 입력값만 틀린 경우다.
 */
public class InvalidPasswordException extends DomainException {

    public InvalidPasswordException() {
        super("INVALID_PASSWORD", HttpStatus.BAD_REQUEST, "Invalid Password", "비밀번호가 올바르지 않습니다.");
    }
}
