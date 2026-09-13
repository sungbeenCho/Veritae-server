package com.veritae.veritae_server.detection;

/**
 * 이미지 분석 한 번의 결과 전체(AI판독 + 사기감지). 데스크톱 탐지 서버가 한 응답에
 * ai_detection/scam_detection을 같이 담아 보내주므로, HTTP 호출은 여전히 한 번뿐이다.
 */
public record ImageAnalysisResult(ImageDetectionResult aiDetection, ScamDetectionResult scamDetection) {
}
