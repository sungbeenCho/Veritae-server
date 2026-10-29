package com.veritae.veritae_server.analysis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MediaSignaturesTest {

    @Test
    void looksLikeImage_shouldAcceptJpegPngWebp() {
        assertThat(MediaSignatures.looksLikeImage(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0))).isTrue();
        assertThat(MediaSignatures.looksLikeImage(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D))).isTrue();
        assertThat(MediaSignatures.looksLikeImage(riff("WEBP"))).isTrue();
    }

    @Test
    void looksLikeAudio_shouldAcceptWavMp3AacM4a() {
        assertThat(MediaSignatures.looksLikeAudio(riff("WAVE"))).isTrue();
        assertThat(MediaSignatures.looksLikeAudio(ascii("ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"))).isTrue();
        // 실제 시험 파일(scam_test_audio.mp3)의 시작: MPEG 프레임 동기 FF F3
        assertThat(MediaSignatures.looksLikeAudio(bytes(0xFF, 0xF3, 0x84, 0xC4, 0, 0, 0, 0, 0, 0, 0, 0))).isTrue();
        assertThat(MediaSignatures.looksLikeAudio(bytes(0xFF, 0xF1, 0x50, 0x80, 0, 0, 0, 0, 0, 0, 0, 0))).isTrue();
        assertThat(MediaSignatures.looksLikeAudio(isoBmff("M4A "))).isTrue();
    }

    @Test
    void looksLikeVideo_shouldAcceptMp4QuickTimeAvi() {
        // 실제 시험 파일(scam_test_video.mp4)의 시작: 00 00 00 20 'ftyp' 'isom'
        assertThat(MediaSignatures.looksLikeVideo(isoBmff("isom"))).isTrue();
        assertThat(MediaSignatures.looksLikeVideo(isoBmff("qt  "))).isTrue();
        assertThat(MediaSignatures.looksLikeVideo(ascii("\u0000\u0000\u0000\u0008wide\u0000\u0000\u0000\u0000"))).isTrue();
        assertThat(MediaSignatures.looksLikeVideo(riff("AVI "))).isTrue();
    }

    @Test
    void shouldRejectNonMediaFiles() {
        byte[] exe = bytes(0x4D, 0x5A, 0x90, 0x00, 0x03, 0, 0, 0, 0x04, 0, 0, 0);
        byte[] zip = bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0, 0, 0, 0x08, 0, 0, 0);
        byte[] text = ascii("hello world!");
        byte[] fake = ascii("fake-bytes");

        for (byte[] head : new byte[][]{exe, zip, text, fake}) {
            assertThat(MediaSignatures.looksLikeImage(head)).isFalse();
            assertThat(MediaSignatures.looksLikeAudio(head)).isFalse();
            assertThat(MediaSignatures.looksLikeVideo(head)).isFalse();
        }
    }

    @Test
    void shouldNotMixModalities() {
        // RIFF 컨테이너는 이미지(WEBP)·음성(WAVE)·영상(AVI)이 같은 머리를 쓰므로 종류까지 봐야 한다.
        assertThat(MediaSignatures.looksLikeImage(riff("WAVE"))).isFalse();
        assertThat(MediaSignatures.looksLikeAudio(riff("WEBP"))).isFalse();
        assertThat(MediaSignatures.looksLikeVideo(riff("WAVE"))).isFalse();
        assertThat(MediaSignatures.looksLikeImage(isoBmff("isom"))).isFalse();
    }

    @Test
    void shouldRejectTooShortInput() {
        assertThat(MediaSignatures.looksLikeImage(bytes(0xFF, 0xD8))).isFalse();
        assertThat(MediaSignatures.looksLikeVideo(new byte[0])).isFalse();
    }

    private static byte[] riff(String kind) {
        return ascii("RIFF$\u0000\u0000\u0000" + kind);
    }

    private static byte[] isoBmff(String brand) {
        return ascii("\u0000\u0000\u0000 ftyp" + brand);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
