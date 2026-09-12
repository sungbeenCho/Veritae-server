package com.veritae.veritae_server.detection;

/**
 * 이미지 AI 판독 결과. SPAI는 근거 카드(evidence)를 만들지 않아 그 필드는 없다.
 * evidenceImage는 SPAI의 attention 히트맵(원본 사진 위에 합성된 오버레이, base64 PNG) -
 * best-effort라 생성 실패 시 null(2026-09-12 구현, spai_runner.py의
 * TEST.EXPORT_IMAGE_PATCHES 기반. 아직 실제 데스크탑/GPU로 검증 전).
 */
public record ImageDetectionResult(String model, double score, String evidenceImage) {
}
