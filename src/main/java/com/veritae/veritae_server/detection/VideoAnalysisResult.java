package com.veritae.veritae_server.detection;

public record VideoAnalysisResult(VideoDetectionResult aiDetection, ScamDetectionResult scamDetection) {
}
