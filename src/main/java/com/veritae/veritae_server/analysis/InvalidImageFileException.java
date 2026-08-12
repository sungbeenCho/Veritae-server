package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 업로드된 이미지 파일이 비어있거나 지원하지 않는 형식인 경우.
 */
public class InvalidImageFileException extends DomainException {

    public InvalidImageFileException(String message) {
        super("INVALID_IMAGE_FILE", HttpStatus.BAD_REQUEST, "Invalid Image File", message);
    }
}
