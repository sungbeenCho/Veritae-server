package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AnalysisJobView;
import com.veritae.veritae_server.detection.AudioAnalysisResult;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.VideoDetectionResult;
import com.veritae.veritae_server.openapi.model.AnalysisJobAcceptedResponse;
import com.veritae.veritae_server.openapi.model.AnalysisJobResponse;
import com.veritae.veritae_server.openapi.model.AudioAnalysisResponse;
import com.veritae.veritae_server.openapi.model.Evidence;
import com.veritae.veritae_server.openapi.model.ImageAnalysisResponse;
import com.veritae.veritae_server.openapi.model.ScamEvidence;

import java.util.List;
import java.util.UUID;

public final class AnalysisApiMapper {

    private AnalysisApiMapper() {
    }

    public static ImageAnalysisResponse toResponse(ImageAnalysisResult result) {
        var openApiResult = new com.veritae.veritae_server.openapi.model.ImageDetectionResult(
                result.aiDetection().model(), result.aiDetection().score())
                .evidenceImage(result.aiDetection().evidenceImage());
        return new ImageAnalysisResponse(openApiResult)
                .scamDetection(toOpenApiScamDetection(result.scamDetection()));
    }

    public static AudioAnalysisResponse toAudioResponse(AudioAnalysisResult result) {
        var openApiResult = new com.veritae.veritae_server.openapi.model.AudioDetectionResult(
                result.aiDetection().model(), result.aiDetection().score(), toOpenApiEvidence(result.aiDetection().evidence()));
        return new AudioAnalysisResponse(openApiResult)
                .scamDetection(toOpenApiScamDetection(result.scamDetection()));
    }

    public static AnalysisJobAcceptedResponse toJobAcceptedResponse(UUID jobId) {
        return new AnalysisJobAcceptedResponse(jobId);
    }

    public static AnalysisJobResponse toJobResponse(AnalysisJobView view) {
        VideoAnalysisResult result = view.result();
        var aiDetection = result != null ? toOpenApiResult(result.aiDetection()) : null;
        var scamDetection = result != null ? toOpenApiScamDetection(result.scamDetection()) : null;
        var status = AnalysisJobResponse.StatusEnum.fromValue(view.status().name());
        return new AnalysisJobResponse(view.jobId(), status)
                .aiDetection(aiDetection)
                .scamDetection(scamDetection)
                .errorCode(view.errorCode())
                .errorMessage(view.errorMessage());
    }

    private static com.veritae.veritae_server.openapi.model.VideoDetectionResult toOpenApiResult(VideoDetectionResult result) {
        return new com.veritae.veritae_server.openapi.model.VideoDetectionResult(
                result.model(), result.score(), toOpenApiEvidence(result.evidence()))
                .evidenceImage(result.evidenceImage());
    }

    private static com.veritae.veritae_server.openapi.model.ScamDetectionResult toOpenApiScamDetection(
            com.veritae.veritae_server.detection.ScamDetectionResult scamDetection) {
        if (scamDetection == null) {
            return null;
        }
        List<ScamEvidence> evidence = scamDetection.evidence().stream()
                .map(e -> new ScamEvidence(e.sentence(), e.score()))
                .toList();
        return new com.veritae.veritae_server.openapi.model.ScamDetectionResult(
                scamDetection.model(), scamDetection.score(), evidence);
    }

    private static List<Evidence> toOpenApiEvidence(List<com.veritae.veritae_server.detection.Evidence> evidence) {
        return evidence.stream()
                .map(e -> new Evidence(e.title(), e.description(), e.tags(), e.startSec(), e.endSec()))
                .toList();
    }
}
