package com.veritae.veritae_server.detection;

import java.util.List;

/**
 * 사기(보이스피싱 등) 위험도 판독 결과. 텍스트가 전혀 추출되지 않으면 이 타입 자체가
 * null이 된다 - AnalysisResponse에서 aiDetection과 나란한 형제 필드다(중첩되지 않음).
 */
public record ScamDetectionResult(String model, double score, List<ScamEvidence> evidence) {
}
