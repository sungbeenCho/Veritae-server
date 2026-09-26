package com.veritae.veritae_server.detection;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 음성 재생 길이가 5분을 넘음. 길이는 오디오를 풀어볼 수 있는 탐지 서버(ffprobe)가 재고, 모델을 돌리기
 * 전에 400 으로 거부한다 - 앞부분만 잘라 분석하면 뒤에 나온 사기 발화를 오류 없이 놓치게 되기 때문이다.
 */
public class AudioTooLongException extends DomainException {

    public AudioTooLongException(String message) {
        super("AUDIO_TOO_LONG", HttpStatus.BAD_REQUEST, "Audio Too Long", message);
    }
}
