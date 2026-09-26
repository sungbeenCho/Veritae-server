package com.veritae.veritae_server.domain.analysisrecord;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisRecordTest {

    @Test
    void submit_shouldCreatePendingVideoRecord() {
        UUID memberId = UUID.randomUUID();

        AnalysisRecord record = AnalysisRecord.submit(memberId);

        assertThat(record.getId()).isNotNull();
        assertThat(record.getMemberId()).isEqualTo(memberId);
        assertThat(record.getModality()).isEqualTo(Modality.VIDEO);
        assertThat(record.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        assertThat(record.getResultJson()).isNull();
        assertThat(record.getAiScore()).isNull();
        assertThat(record.getScamScore()).isNull();
    }

    @Test
    void completedSync_shouldCreateCompletedImageRecordWithScores() {
        UUID memberId = UUID.randomUUID();

        AnalysisRecord record = AnalysisRecord.completedSync(
                memberId, Modality.IMAGE, "{\"score\":0.87}", 0.87, null);

        assertThat(record.getModality()).isEqualTo(Modality.IMAGE);
        assertThat(record.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(record.getResultJson()).isEqualTo("{\"score\":0.87}");
        assertThat(record.getAiScore()).isEqualTo(0.87);
        assertThat(record.getScamScore()).isNull();
    }

    @Test
    void markProcessing_shouldTransitionToProcessing() {
        AnalysisRecord record = AnalysisRecord.submit(UUID.randomUUID());

        record.markProcessing();

        assertThat(record.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
    }

    @Test
    void markCompleted_shouldTransitionToCompletedAndStoreResultAndScores() {
        AnalysisRecord record = AnalysisRecord.submit(UUID.randomUUID());

        record.markCompleted("{\"score\":0.91}", 0.91, 0.2);

        assertThat(record.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(record.getResultJson()).isEqualTo("{\"score\":0.91}");
        assertThat(record.getAiScore()).isEqualTo(0.91);
        assertThat(record.getScamScore()).isEqualTo(0.2);
    }

    @Test
    void markCompletedWithPartialError_shouldStayCompletedAndStoreResultScoresAndErrorTogether() {
        AnalysisRecord record = AnalysisRecord.submit(UUID.randomUUID());

        record.markCompletedWithPartialError(
                "{\"scamDetection\":{\"score\":0.82}}", null, 0.82, "NO_FACE_DETECTED", "얼굴을 찾을 수 없습니다.");

        assertThat(record.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(record.getResultJson()).isEqualTo("{\"scamDetection\":{\"score\":0.82}}");
        assertThat(record.getAiScore()).isNull();
        assertThat(record.getScamScore()).isEqualTo(0.82);
        assertThat(record.getErrorCode()).isEqualTo("NO_FACE_DETECTED");
        assertThat(record.getErrorMessage()).isEqualTo("얼굴을 찾을 수 없습니다.");
    }

    @Test
    void markFailed_shouldTransitionToFailedAndStoreErrorCodeAndMessage() {
        AnalysisRecord record = AnalysisRecord.submit(UUID.randomUUID());

        record.markFailed("ANALYSIS_FAILED", "탐지 서버 호출 실패");

        assertThat(record.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(record.getErrorCode()).isEqualTo("ANALYSIS_FAILED");
        assertThat(record.getErrorMessage()).isEqualTo("탐지 서버 호출 실패");
    }

    @Test
    void createdAt_shouldBeTruncatedToMicrosecondsToMatchDatabasePrecision() {
        AnalysisRecord record = AnalysisRecord.submit(UUID.randomUUID());

        assertThat(record.getCreatedAt().getNano() % 1_000).isZero();
    }

    @Test
    void newRecord_shouldHaveNoMedia() {
        AnalysisRecord record = AnalysisRecord.completedSync(UUID.randomUUID(), Modality.IMAGE, "{}", 0.1, null);

        assertThat(record.getMediaKey()).isNull();
        assertThat(record.hasMedia()).isFalse();
    }

    @Test
    void attachMedia_shouldRecordStorageKey() {
        AnalysisRecord record = AnalysisRecord.completedSync(UUID.randomUUID(), Modality.IMAGE, "{}", 0.1, null);

        record.attachMedia("media/member/record");

        assertThat(record.getMediaKey()).isEqualTo("media/member/record");
        assertThat(record.hasMedia()).isTrue();
    }
}
