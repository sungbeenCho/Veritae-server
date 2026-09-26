package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AnalysisHistoryService;
import com.veritae.veritae_server.analysis.AnalysisJobView;
import com.veritae.veritae_server.analysis.AnalysisOutcome;
import com.veritae.veritae_server.detection.AudioAnalysisResult;
import com.veritae.veritae_server.detection.AudioDetectionResult;
import com.veritae.veritae_server.detection.Evidence;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
import com.veritae.veritae_server.detection.ImageDetectionResult;
import com.veritae.veritae_server.detection.ScamDetectionResult;
import com.veritae.veritae_server.detection.ScamEvidence;
import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.Modality;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisApiMapperTest {

    @Test
    void toRecordSummary_withImageRecord_shouldFillImageDetectionOnly() throws Exception {
        var scam = new ScamDetectionResult("lilju", 0.82, List.of(new ScamEvidence("계좌번호를 알려주세요", 0.95)));
        var result = new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, "base64png"), scam);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var record = AnalysisRecord.completedSync(
                UUID.randomUUID(), Modality.IMAGE, objectMapper.writeValueAsString(result), 0.87, 0.82);

        var summary = AnalysisApiMapper.toRecordSummary(record);

        assertThat(summary.getModality().name()).isEqualTo("IMAGE");
        assertThat(summary.getImageDetection().getModel()).isEqualTo("spai");
        assertThat(summary.getImageDetection().getScore()).isEqualTo(0.87);
        assertThat(summary.getAudioDetection()).isNull();
        assertThat(summary.getVideoDetection()).isNull();
        assertThat(summary.getScamDetection().getScore()).isEqualTo(0.82);
    }

    @Test
    void toRecordSummary_withAudioRecord_shouldFillAudioDetectionOnly() throws Exception {
        var evidence = List.of(new Evidence(
                "합성 음성 의심 구간", "1.0초~4.0초 구간에서 부자연스러운 음성 합성 흔적이 감지됨",
                List.of("temporal"), 1.0, 4.0));
        var scam = new ScamDetectionResult("lilju", 0.82, List.of(new ScamEvidence("계좌번호를 알려주세요", 0.95)));
        var result = new AudioAnalysisResult(new AudioDetectionResult("antideepfake", 0.73, evidence), scam);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var record = AnalysisRecord.completedSync(
                UUID.randomUUID(), Modality.AUDIO, objectMapper.writeValueAsString(result), 0.73, 0.82);

        var summary = AnalysisApiMapper.toRecordSummary(record);

        assertThat(summary.getModality().name()).isEqualTo("AUDIO");
        assertThat(summary.getAudioDetection().getModel()).isEqualTo("antideepfake");
        assertThat(summary.getAudioDetection().getScore()).isEqualTo(0.73);
        assertThat(summary.getAudioDetection().getEvidence()).hasSize(1);
        assertThat(summary.getAudioDetection().getEvidence().get(0).getTitle()).isEqualTo("합성 음성 의심 구간");
        assertThat(summary.getAudioDetection().getEvidence().get(0).getStartSec()).isEqualTo(1.0);
        assertThat(summary.getImageDetection()).isNull();
        assertThat(summary.getVideoDetection()).isNull();
        assertThat(summary.getScamDetection().getScore()).isEqualTo(0.82);
        assertThat(summary.getErrorCode()).isNull();
    }

    @Test
    void toRecordSummary_withVideoRecord_shouldFillErrorCodeWhenAiDetectionMissing() throws Exception {
        var scam = new ScamDetectionResult("lilju", 0.82, List.of(new ScamEvidence("계좌번호를 알려주세요", 0.95)));
        var result = new VideoAnalysisResult(null, scam, "NO_FACE_DETECTED");
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // completedSync는 errorCode를 받지 않는다 - 영상은 실제 파이프라인과 동일하게
        // submit(PENDING) 후 markCompletedWithPartialError로 얼굴없음 부분성공을 기록한다.
        var record = AnalysisRecord.submit(UUID.randomUUID());
        record.markCompletedWithPartialError(
                objectMapper.writeValueAsString(result), null, 0.82,
                "NO_FACE_DETECTED", "영상에서 얼굴을 찾을 수 없어 AI판독은 제공되지 않습니다.");

        var summary = AnalysisApiMapper.toRecordSummary(record);

        assertThat(summary.getModality().name()).isEqualTo("VIDEO");
        assertThat(summary.getVideoDetection()).isNull();
        assertThat(summary.getErrorCode()).isEqualTo("NO_FACE_DETECTED");
        assertThat(summary.getScamDetection().getModel()).isEqualTo("lilju");
        assertThat(summary.getScamDetection().getScore()).isEqualTo(0.82);
        assertThat(summary.getImageDetection()).isNull();
        assertThat(summary.getAudioDetection()).isNull();
    }

    @Test
    void toRecordListResponse_shouldMapContentOnly() throws Exception {
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var result = new ImageAnalysisResult(new ImageDetectionResult("spai", 0.1, null), null);
        var record = AnalysisRecord.completedSync(
                UUID.randomUUID(), Modality.IMAGE, objectMapper.writeValueAsString(result), 0.1, null);

        var response = AnalysisApiMapper.toRecordListResponse(List.of(record));

        assertThat(response.getContent()).hasSize(1);
    }

    @Test
    void toResponse_shouldIncludeRecordIdAsId() {
        UUID recordId = UUID.randomUUID();
        var result = new ImageAnalysisResult(new ImageDetectionResult("spai", 0.1, null), null);

        var response = AnalysisApiMapper.toResponse(new AnalysisOutcome<>(recordId, result));

        assertThat(response.getId()).isEqualTo(recordId);
        assertThat(response.getAiDetection().getModel()).isEqualTo("spai");
    }

    @Test
    void toAudioResponse_shouldIncludeRecordIdAsId() {
        UUID recordId = UUID.randomUUID();
        var result = new AudioAnalysisResult(new AudioDetectionResult("antideepfake", 0.2, List.of()), null);

        var response = AnalysisApiMapper.toAudioResponse(new AnalysisOutcome<>(recordId, result));

        assertThat(response.getId()).isEqualTo(recordId);
        assertThat(response.getAiDetection().getModel()).isEqualTo("antideepfake");
    }

    @Test
    void toRecordSummary_withPartialErrorVideoRecord_shouldIncludeErrorMessage() throws Exception {
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var record = AnalysisRecord.submit(UUID.randomUUID());
        record.markCompletedWithPartialError(
                objectMapper.writeValueAsString(new VideoAnalysisResult(null, null, "NO_FACE_DETECTED")), null, null,
                "NO_FACE_DETECTED", "영상에서 얼굴을 찾을 수 없어 AI판독은 제공되지 않습니다.");

        var summary = AnalysisApiMapper.toRecordSummary(record);

        assertThat(summary.getErrorMessage()).isEqualTo("영상에서 얼굴을 찾을 수 없어 AI판독은 제공되지 않습니다.");
    }

    @Test
    void toRecordSummary_shouldReportWhetherOriginalIsAvailable() throws Exception {
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = objectMapper.writeValueAsString(new ImageAnalysisResult(new ImageDetectionResult("spai", 0.1, null), null));
        var withMedia = AnalysisRecord.completedSync(UUID.randomUUID(), Modality.IMAGE, json, 0.1, null);
        withMedia.attachMedia("media/m/r");
        var withoutMedia = AnalysisRecord.completedSync(UUID.randomUUID(), Modality.IMAGE, json, 0.1, null);

        assertThat(AnalysisApiMapper.toRecordSummary(withMedia).getMediaAvailable()).isTrue();
        assertThat(AnalysisApiMapper.toRecordSummary(withoutMedia).getMediaAvailable()).isFalse();
    }

    @Test
    void toJobResponse_withPendingJob_shouldIncludeJobsAhead() {
        var view = new AnalysisJobView(UUID.randomUUID(),
                com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus.PENDING, null, null, null, 2);

        var response = AnalysisApiMapper.toJobResponse(view);

        assertThat(response.getJobsAhead()).isEqualTo(2);
    }

    @Test
    void toReportResponse_shouldMapAllCounts() {
        var view = new AnalysisHistoryService.AnalysisReportView(23, 10, 8, 5, 3, 2);

        var response = AnalysisApiMapper.toReportResponse(view);

        assertThat(response.getTotalCount()).isEqualTo(23);
        assertThat(response.getImageCount()).isEqualTo(10);
        assertThat(response.getAudioCount()).isEqualTo(8);
        assertThat(response.getVideoCount()).isEqualTo(5);
        assertThat(response.getAiDetectedCount()).isEqualTo(3);
        assertThat(response.getScamDetectedCount()).isEqualTo(2);
    }
}
