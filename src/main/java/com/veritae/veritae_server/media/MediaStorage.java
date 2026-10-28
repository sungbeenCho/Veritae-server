package com.veritae.veritae_server.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 분석 원본 파일을 보관하는 외부 저장소(Cloudflare R2). 파일 자체는 여기 두고, DB 에는 파일 위치(key)만
 * 기록한다 - 서버 디스크나 DB 에 파일을 직접 넣으면 용량/이전/확장 문제가 생겨서다.
 */
public interface MediaStorage {

    /** 저장소가 설정돼 있는지. false 면 원본 보관 기능이 꺼진 상태로 동작한다. */
    boolean enabled();

    void put(String key, byte[] bytes, String contentType);

    /** 큰 파일용. 기본 구현은 파일을 읽어 위 put 으로 넘긴다 - R2 구현은 파일에서 바로 흘려 보낸다. */
    default void put(String key, Path file, String contentType) {
        try {
            put(key, Files.readAllBytes(file), contentType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param range HTTP Range 헤더 값(예: "bytes=0-1023"). null 이면 파일 전체.
     * @throws MediaObjectNotFoundException 파일이 없을 때
     * @throws MediaRangeNotSatisfiableException 범위가 파일 크기를 벗어날 때
     */
    MediaObject get(String key, String range);

    void delete(String key);

    /** prefix 아래의 파일을 전부 지운다. prefix 는 비어 있으면 안 되고 '/' 로 끝나야 한다. */
    void deleteByPrefix(String prefix);
}
