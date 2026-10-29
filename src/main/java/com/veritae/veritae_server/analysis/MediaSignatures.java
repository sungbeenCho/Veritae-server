package com.veritae.veritae_server.analysis;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

/**
 * 업로드한 파일이 꼬리표(Content-Type)뿐 아니라 내용도 미디어인지, 파일 앞부분의 형식 표시(매직 넘버)로 확인한다.
 * 꼬리표는 보내는 쪽이 마음대로 붙일 수 있어, 미디어가 아닌 파일이 탐지 서버(데스크탑)의 OCR·ffmpeg·모델까지 가는 것을 막는다.
 * 같은 종류(이미지·음성·영상) 안에서는 꼬리표와 실제 형식이 달라도 통과시킨다 - 휴대폰이 꼬리표를 부정확하게 붙이는 일이 흔하다.
 */
public final class MediaSignatures {

    /** 판정에 필요한 앞부분 길이. RIFF(12바이트)와 ISO BMFF(8바이트)를 모두 볼 수 있다. */
    static final int HEAD_LENGTH = 12;

    /** ISO BMFF(MP4·M4A·MOV)는 4~7번째 바이트가 첫 상자 이름이다. 오래된 QuickTime은 ftyp 없이 다른 상자로 시작한다. */
    private static final Set<String> ISO_BMFF_FIRST_BOXES = Set.of("ftyp", "moov", "mdat", "wide", "free", "skip", "pnot");

    private MediaSignatures() {
    }

    public static boolean looksLikeImage(byte[] head) {
        return startsWith(head, 0xFF, 0xD8, 0xFF)                                    // JPEG
                || startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)  // PNG
                || isRiff(head, "WEBP");
    }

    public static boolean looksLikeAudio(byte[] head) {
        return isRiff(head, "WAVE")
                || ascii(head, 0, "ID3")                                             // ID3 태그로 시작하는 MP3
                || isMpegFrameSync(head)                                             // 태그 없는 MP3, ADTS AAC
                || isIsoBmff(head);                                                  // M4A
    }

    public static boolean looksLikeVideo(byte[] head) {
        return isIsoBmff(head) || isRiff(head, "AVI ");                              // MP4·MOV, AVI
    }

    /** 앞부분만 읽는다 - 영상은 최대 100MB라 통째로 읽지 않는다. */
    public static byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(HEAD_LENGTH);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isRiff(byte[] head, String kind) {
        return ascii(head, 0, "RIFF") && ascii(head, 8, kind);
    }

    private static boolean isIsoBmff(byte[] head) {
        return head.length >= 8 && ISO_BMFF_FIRST_BOXES.contains(new String(head, 4, 4, StandardCharsets.ISO_8859_1));
    }

    /** MPEG 오디오 프레임 동기 신호: 11비트가 모두 1(FF Ex~FF Fx). MP3와 ADTS AAC가 이렇게 시작한다. */
    private static boolean isMpegFrameSync(byte[] head) {
        return head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0;
    }

    private static boolean ascii(byte[] head, int offset, String expected) {
        byte[] bytes = expected.getBytes(StandardCharsets.ISO_8859_1);
        return head.length >= offset + bytes.length
                && Arrays.equals(head, offset, offset + bytes.length, bytes, 0, bytes.length);
    }

    private static boolean startsWith(byte[] head, int... expected) {
        if (head.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((head[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
