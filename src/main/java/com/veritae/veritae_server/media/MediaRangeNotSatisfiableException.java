package com.veritae.veritae_server.media;

/** Range 요청의 범위가 파일 크기를 벗어남(저장소가 416 을 돌려준 경우). */
public class MediaRangeNotSatisfiableException extends RuntimeException {

    public MediaRangeNotSatisfiableException(String key, String range, Throwable cause) {
        super("요청 범위가 파일 크기를 벗어납니다: key=" + key + ", range=" + range, cause);
    }
}
