package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AnalysisJobView;
import com.veritae.veritae_server.detection.AudioDetectionResult;
import com.veritae.veritae_server.detection.ImageDetectionResult;
import com.veritae.veritae_server.detection.VideoDetectionResult;
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

    public static ImageAnalysisResponse toResponse(ImageDetectionResult result) {
        var openApiResult = new com.veritae.veritae_server.openapi.model.ImageDetectionResult(
                result.model(), result.score())
                .evidenceImage(result.evidenceImage());
        return new ImageAnalysisResponse(openApiResult);
    }

    public static AudioAnalysisResponse toAudioResponse(AudioDetectionResult result) {
        var openApiResult = new com.veritae.veritae_server.openapi.model.AudioDetectionResult(
                result.model(), result.score(), toOpenApiEvidence(result.evidence()));
        return new AudioAnalysisResponse(openApiResult);
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

    private static com.veritae.veritae_server.openapi.model.VideoDetectionResult toOpenApiResult(VideoDetectionResult result) {
        return new com.veritae.veritae_server.openapi.model.VideoDetectionResult(
                result.model(), result.score(), toOpenApiEvidence(result.evidence()))
                .evidenceImage(result.evidenceImage());
    }

    private static List<Evidence> toOpenApiEvidence(List<com.veritae.veritae_server.detection.Evidence> evidence) {
        return evidence.stream()
                .map(e -> new Evidence(e.title(), e.description(), e.tags(), e.startSec(), e.endSec()))
                .toList();
    }
}
