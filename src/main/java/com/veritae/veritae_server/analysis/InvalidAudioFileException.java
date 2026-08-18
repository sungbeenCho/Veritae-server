package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 업로드된 음성 파일이 비어있거나 지원하지 않는 형식/용량인 경우.
 */
public class InvalidAudioFileException extends DomainException {

    public InvalidAudioFileException(String message) {
        super("INVALID_AUDIO_FILE", HttpStatus.BAD_REQUEST, "Invalid Audio File", message);
    }
}
