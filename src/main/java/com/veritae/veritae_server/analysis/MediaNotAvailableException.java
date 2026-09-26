package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/** 본인 기록은 맞지만 원본 파일이 없음(보관 기능 이전 기록, 최신 10건 밖, 저장 실패 등). */
public class MediaNotAvailableException extends DomainException {

    public MediaNotAvailableException() {
        super("MEDIA_NOT_AVAILABLE", HttpStatus.NOT_FOUND, "Media Not Available",
                "이 기록의 원본 파일이 없습니다.");
    }
}
