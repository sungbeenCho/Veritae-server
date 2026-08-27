package com.veritae.veritae_server.detection;

import java.util.List;

/**
 * 음성 AI 판독 결과. 공간적 근거(이미지) 개념이 없어 evidenceImage 필드는 없다
 * (2026-08-27, 이미지/음성/영상이 하나의 공통 타입을 같이 쓰던 걸 모달리티별로 분리).
 */
public record AudioDetectionResult(String model, double score, List<Evidence> evidence) {
}
