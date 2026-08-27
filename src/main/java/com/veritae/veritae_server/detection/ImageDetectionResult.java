package com.veritae.veritae_server.detection;

/**
 * 이미지 AI 판독 결과. SPAI는 근거 카드(evidence)를 만들지 않아 그 필드는 없다.
 * evidenceImage는 히트맵 기능(설계 승인, 구현 보류 중)을 위해 예약된 필드 - 아직
 * 미구현이라 항상 null(2026-08-27, 이미지/음성/영상이 하나의 공통 타입을 같이 쓰던 걸
 * 모달리티별로 분리 - 안 쓰는 필드가 매번 같이 나오는 게 혼란스럽다는 지적으로 분리함).
 */
public record ImageDetectionResult(String model, double score, String evidenceImage) {
}
