package com.veritae.veritae_server.detection;

/**
 * aiDetection은 얼굴을 못 찾으면 null일 수 있다(정상적인 한계, 장애 아님) - 이때
 * errorCode가 이유("NO_FACE_DETECTED")를 담는다. aiDetection이 있으면 errorCode는 항상 null이다.
 */
public record VideoAnalysisResult(VideoDetectionResult aiDetection, ScamDetectionResult scamDetection, String errorCode) {
}
