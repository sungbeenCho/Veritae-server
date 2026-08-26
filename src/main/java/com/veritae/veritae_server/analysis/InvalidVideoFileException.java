package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 업로드된 영상 파일이 비어있거나 지원하지 않는 형식/용량인 경우.
 */
public class InvalidVideoFileException extends DomainException {

    public InvalidVideoFileException(String message) {
        super("INVALID_VIDEO_FILE", HttpStatus.BAD_REQUEST, "Invalid Video File", message);
    }
}
