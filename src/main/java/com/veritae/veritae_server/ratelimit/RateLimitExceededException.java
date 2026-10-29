package com.veritae.veritae_server.ratelimit;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/** 정해진 시간 안의 요청 횟수를 넘었다. 응답에 Retry-After(초)를 함께 보낸다. */
public class RateLimitExceededException extends DomainException {

    public static final String USER_MESSAGE = "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.";

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super("TOO_MANY_REQUESTS", HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", USER_MESSAGE);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
