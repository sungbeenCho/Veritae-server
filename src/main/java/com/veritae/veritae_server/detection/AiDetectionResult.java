package com.veritae.veritae_server.detection;

import java.util.List;

/**
 * evidenceImage는 판독 근거 히트맵(base64 PNG)이다. 이미지/음성 어댑터는 아직 히트맵을
 * 만들지 않아 항상 null을 넘긴다 - 영상(DfdcHttpDetectionClient)만 채운다.
 */
public record AiDetectionResult(String model, double score, List<Evidence> evidence, String evidenceImage) {
}
