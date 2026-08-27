package com.veritae.veritae_server.detection;

import java.util.List;

/**
 * 영상 AI 판독 결과. 시간 구간 근거와 공간적 히트맵을 둘 다 지원하는 유일한 포맷이라
 * evidence/evidenceImage 둘 다 갖는다(2026-08-27, 이미지/음성/영상이 하나의 공통 타입을
 * 같이 쓰던 걸 모달리티별로 분리 - 예전 detection.AiDetectionResult를 대체).
 */
public record VideoDetectionResult(String model, double score, List<Evidence> evidence, String evidenceImage) {
}
