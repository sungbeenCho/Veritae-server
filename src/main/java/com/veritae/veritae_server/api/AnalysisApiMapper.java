package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AnalysisJobView;
import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.openapi.model.AnalysisJobAcceptedResponse;
import com.veritae.veritae_server.openapi.model.AnalysisJobResponse;
import com.veritae.veritae_server.openapi.model.AudioAnalysisResponse;
import com.veritae.veritae_server.openapi.model.Evidence;
import com.veritae.veritae_server.openapi.model.ImageAnalysisResponse;

import java.util.List;
import java.util.UUID;

public final class AnalysisApiMapper {

    private AnalysisApiMapper() {
    }

    public static ImageAnalysisResponse toResponse(AiDetectionResult result) {
        return new ImageAnalysisResponse(toOpenApiResult(result));
    }

    public static AudioAnalysisResponse toAudioResponse(AiDetectionResult result) {
        return new AudioAnalysisResponse(toOpenApiResult(result));
    }

    public static AnalysisJobAcceptedResponse toJobAcceptedResponse(UUID jobId) {
        return new AnalysisJobAcceptedResponse(jobId);
    }

    public static AnalysisJobResponse toJobResponse(AnalysisJobView view) {
        var aiDetection = view.result() != null ? toOpenApiResult(view.result()) : null;
        var status = AnalysisJobResponse.StatusEnum.fromValue(view.status().name());
        return new AnalysisJobResponse(view.jobId(), status)
                .aiDetection(aiDetection)
                .errorMessage(view.errorMessage());
    }

    private static com.veritae.veritae_server.openapi.model.AiDetectionResult toOpenApiResult(AiDetectionResult result) {
        List<Evidence> evidence = result.evidence().stream()
                .map(e -> new Evidence(e.title(), e.description(), e.tags(), e.startSec(), e.endSec()))
                .toList();
        return new com.veritae.veritae_server.openapi.model.AiDetectionResult(
                result.model(), result.score(), evidence)
                .evidenceImage(result.evidenceImage());
    }
}
