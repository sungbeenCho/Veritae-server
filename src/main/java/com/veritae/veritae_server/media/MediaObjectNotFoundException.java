package com.veritae.veritae_server.media;

/** 저장소에 해당 key 의 파일이 없음. 클라이언트 응답으로 바꾸는 건 호출한 쪽의 몫이다. */
public class MediaObjectNotFoundException extends RuntimeException {

    public MediaObjectNotFoundException(String key) {
        super("저장소에 파일이 없습니다: " + key);
    }
}
