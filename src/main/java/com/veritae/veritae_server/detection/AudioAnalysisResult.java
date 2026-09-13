package com.veritae.veritae_server.detection;

public record AudioAnalysisResult(AudioDetectionResult aiDetection, ScamDetectionResult scamDetection) {
}
