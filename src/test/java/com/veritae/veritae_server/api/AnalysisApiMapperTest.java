package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AnalysisHistoryService;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
import com.veritae.veritae_server.detection.ImageDetectionResult;
import com.veritae.veritae_server.detection.ScamDetectionResult;
import com.veritae.veritae_server.detection.ScamEvidence;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.Modality;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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
    void toRecordListResponse_shouldMapPageMetadata() throws Exception {
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var result = new ImageAnalysisResult(new ImageDetectionResult("spai", 0.1, null), null);
        var record = AnalysisRecord.completedSync(
                UUID.randomUUID(), Modality.IMAGE, objectMapper.writeValueAsString(result), 0.1, null);
        var page = new PageImpl<>(List.of(record), PageRequest.of(1, 10), 23);

        var response = AnalysisApiMapper.toRecordListResponse(page);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getPage()).isEqualTo(1);
        assertThat(response.getSize()).isEqualTo(10);
        assertThat(response.getTotalElements()).isEqualTo(23);
        assertThat(response.getTotalPages()).isEqualTo(3);
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
