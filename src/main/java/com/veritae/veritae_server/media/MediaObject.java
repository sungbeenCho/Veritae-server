package com.veritae.veritae_server.media;

import java.io.InputStream;

/**
 * 저장소에서 꺼낸 파일. content 는 호출한 쪽이 다 읽고 닫아야 한다(응답으로 흘려보내면 Spring 이 닫는다).
 *
 * @param contentLength 이번에 내려가는 바이트 수(Range 요청이면 그 부분의 길이)
 * @param contentRange Range 요청일 때 저장소가 돌려준 Content-Range 값(예: "bytes 0-1023/5000"), 아니면 null
 */
public record MediaObject(InputStream content, String contentType, long contentLength, String contentRange) {

    public boolean partial() {
        return contentRange != null;
    }
}
