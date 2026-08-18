package com.veritae.veritae_server.api;

import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.openapi.model.AudioAnalysisResponse;
import com.veritae.veritae_server.openapi.model.Evidence;
import com.veritae.veritae_server.openapi.model.ImageAnalysisResponse;

import java.util.List;

public final class AnalysisApiMapper {

    private AnalysisApiMapper() {
    }

    public static ImageAnalysisResponse toResponse(AiDetectionResult result) {
        return new ImageAnalysisResponse(toOpenApiResult(result));
    }

    public static AudioAnalysisResponse toAudioResponse(AiDetectionResult result) {
        return new AudioAnalysisResponse(toOpenApiResult(result));
    }

    private static com.veritae.veritae_server.openapi.model.AiDetectionResult toOpenApiResult(AiDetectionResult result) {
        List<Evidence> evidence = result.evidence().stream()
                .map(e -> new Evidence(e.title(), e.description(), e.tags(), e.startSec(), e.endSec()))
                .toList();
        return new com.veritae.veritae_server.openapi.model.AiDetectionResult(result.model(), result.score(), evidence);
    }
}
