package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AnalysisJobView;
import com.veritae.veritae_server.analysis.AnalysisOutcome;
import com.veritae.veritae_server.detection.AudioAnalysisResult;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
import com.veritae.veritae_server.detection.ImageDetectionResult;
import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.VideoDetectionResult;
import com.veritae.veritae_server.openapi.model.AnalysisJobAcceptedResponse;
import com.veritae.veritae_server.openapi.model.AnalysisJobResponse;
import com.veritae.veritae_server.openapi.model.AudioAnalysisResponse;
import com.veritae.veritae_server.openapi.model.Evidence;
import com.veritae.veritae_server.openapi.model.ImageAnalysisResponse;
import com.veritae.veritae_server.openapi.model.ScamEvidence;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public final class AnalysisApiMapper {

    private AnalysisApiMapper() {
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public static ImageAnalysisResponse toResponse(AnalysisOutcome<ImageAnalysisResult> outcome) {
        ImageAnalysisResult result = outcome.result();
        var openApiResult = toOpenApiImageResult(result.aiDetection());
        return new ImageAnalysisResponse(outcome.recordId(), openApiResult)
                .scamDetection(toOpenApiScamDetection(result.scamDetection()));
    }

    public static AudioAnalysisResponse toAudioResponse(AnalysisOutcome<AudioAnalysisResult> outcome) {
        AudioAnalysisResult result = outcome.result();
        var openApiResult = new com.veritae.veritae_server.openapi.model.AudioDetectionResult(
                result.aiDetection().model(), result.aiDetection().score(), toOpenApiEvidence(result.aiDetection().evidence()));
        return new AudioAnalysisResponse(outcome.recordId(), openApiResult)
                .scamDetection(toOpenApiScamDetection(result.scamDetection()));
    }

    public static AnalysisJobAcceptedResponse toJobAcceptedResponse(UUID jobId) {
        return new AnalysisJobAcceptedResponse(jobId);
    }

    public static AnalysisJobResponse toJobResponse(AnalysisJobView view) {
        VideoAnalysisResult result = view.result();
        // result가 있어도 그 안의 aiDetection은 얼굴없음 등의 이유로 null일 수 있다(2026-09-21) -
        // result만 null 체크하면 toOpenApiResult(null) 호출로 NPE가 난다.
        var aiDetection = result != null && result.aiDetection() != null ? toOpenApiResult(result.aiDetection()) : null;
        var scamDetection = result != null ? toOpenApiScamDetection(result.scamDetection()) : null;
        var status = AnalysisJobResponse.StatusEnum.fromValue(view.status().name());
        return new AnalysisJobResponse(view.jobId(), status)
                .aiDetection(aiDetection)
                .scamDetection(scamDetection)
                .errorCode(view.errorCode())
                .errorMessage(view.errorMessage())
                .jobsAhead(view.jobsAhead());
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

    public static com.veritae.veritae_server.openapi.model.AnalysisRecordListResponse toRecordListResponse(
            List<com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord> records) {
        List<com.veritae.veritae_server.openapi.model.AnalysisRecordSummary> content =
                records.stream().map(AnalysisApiMapper::toRecordSummary).toList();
        return new com.veritae.veritae_server.openapi.model.AnalysisRecordListResponse(content);
    }

    public static com.veritae.veritae_server.openapi.model.AnalysisRecordSummary toRecordSummary(
            com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord record) {
        var modality = com.veritae.veritae_server.openapi.model.AnalysisRecordSummary.ModalityEnum
                .fromValue(record.getModality().name());
        var summary = new com.veritae.veritae_server.openapi.model.AnalysisRecordSummary(
                record.getId(), modality, record.getCreatedAt().atOffset(ZoneOffset.UTC), record.hasMedia());
        // 판독 필드가 비어있는 이유(예: NO_FACE_DETECTED)를 클라이언트가 알 수 있게 그대로 내려준다.
        // 문구(errorMessage)도 GET /jobs/{id} 와 같은 값을 내려줘 기록 상세가 분석 직후 화면과 같게 한다.
        summary.errorCode(record.getErrorCode());
        summary.errorMessage(record.getErrorMessage());

        switch (record.getModality()) {
            case IMAGE -> {
                ImageAnalysisResult result = readResult(record.getResultJson(), ImageAnalysisResult.class);
                summary.imageDetection(result.aiDetection() != null ? toOpenApiImageResult(result.aiDetection()) : null);
                summary.scamDetection(toOpenApiScamDetection(result.scamDetection()));
            }
            case AUDIO -> {
                AudioAnalysisResult result = readResult(record.getResultJson(), AudioAnalysisResult.class);
                summary.audioDetection(result.aiDetection() != null
                        ? new com.veritae.veritae_server.openapi.model.AudioDetectionResult(
                                result.aiDetection().model(), result.aiDetection().score(),
                                toOpenApiEvidence(result.aiDetection().evidence()))
                        : null);
                summary.scamDetection(toOpenApiScamDetection(result.scamDetection()));
            }
            case VIDEO -> {
                VideoAnalysisResult result = readResult(record.getResultJson(), VideoAnalysisResult.class);
                summary.videoDetection(result.aiDetection() != null ? toOpenApiResult(result.aiDetection()) : null);
                summary.scamDetection(toOpenApiScamDetection(result.scamDetection()));
            }
        }
        return summary;
    }

    public static com.veritae.veritae_server.openapi.model.AnalysisReportResponse toReportResponse(
            com.veritae.veritae_server.analysis.AnalysisHistoryService.AnalysisReportView view) {
        return new com.veritae.veritae_server.openapi.model.AnalysisReportResponse(
                view.totalCount(), view.imageCount(), view.audioCount(), view.videoCount(),
                view.aiDetectedCount(), view.scamDetectedCount());
    }

    private static com.veritae.veritae_server.openapi.model.ImageDetectionResult toOpenApiImageResult(
            ImageDetectionResult result) {
        return new com.veritae.veritae_server.openapi.model.ImageDetectionResult(result.model(), result.score())
                .evidenceImage(result.evidenceImage());
    }

    private static <T> T readResult(String resultJson, Class<T> type) {
        try {
            return OBJECT_MAPPER.readValue(resultJson, type);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
