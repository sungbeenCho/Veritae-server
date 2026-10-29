package com.veritae.veritae_server.analysis;

import java.nio.charset.StandardCharsets;

/** 업로드 내용 검사(MediaSignatures)를 통과하는 최소한의 가짜 미디어 내용. 실제 디코딩은 하지 않으므로 머리만 맞으면 된다. */
final class MediaFixtures {

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
    static final byte[] WAV = "RIFF$\u0000\u0000\u0000WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] MP4 = "\u0000\u0000\u0000 ftypisom\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);
    /** 이미지·음성·영상 어느 머리와도 맞지 않는 내용(예: 꼬리표만 미디어로 붙인 텍스트 파일). */
    static final byte[] NOT_MEDIA = "this is plain text, not media".getBytes(StandardCharsets.UTF_8);

    private MediaFixtures() {
    }
}
