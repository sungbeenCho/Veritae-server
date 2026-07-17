package com.veritae.veritae_server.auth;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * refreshToken 이 유효하지 않거나 만료된 경우.
 */
public class InvalidRefreshTokenException extends DomainException {

    public InvalidRefreshTokenException() {
        super("INVALID_REFRESH_TOKEN", HttpStatus.UNAUTHORIZED, "Invalid Refresh Token",
                "refresh 토큰이 유효하지 않거나 만료되었습니다.");
    }
}
