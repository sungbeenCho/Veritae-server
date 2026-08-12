package com.veritae.veritae_server.api;

import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.openapi.model.ImageAnalysisResponse;

public final class AnalysisApiMapper {

    private AnalysisApiMapper() {
    }

    public static ImageAnalysisResponse toResponse(AiDetectionResult result) {
        return new ImageAnalysisResponse(
                new com.veritae.veritae_server.openapi.model.AiDetectionResult(result.model(), result.score()));
    }
}
