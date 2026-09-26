package com.veritae.veritae_server.media;

/**
 * R2 설정이 없을 때 쓰는 구현 - 원본을 저장하지 않는다. 로컬 개발이나 R2 계정을 만들기 전에도 서버가
 * 뜨고 분석이 정상 동작하게 하려는 것이다(원본만 없음 = mediaAvailable false, 다운로드 404).
 */
public class DisabledMediaStorage implements MediaStorage {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        throw new IllegalStateException("원본 보관소가 설정되지 않았습니다.");
    }

    @Override
    public MediaObject get(String key, String range) {
        throw new MediaObjectNotFoundException(key);
    }

    @Override
    public void delete(String key) {
        // 저장한 적이 없으니 지울 것도 없다.
    }

    @Override
    public void deleteByPrefix(String prefix) {
        // 저장한 적이 없으니 지울 것도 없다.
    }
}
