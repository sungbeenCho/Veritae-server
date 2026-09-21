package com.veritae.veritae_server.domain.analysisjob;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisJobTest {

    @Test
    void submit_shouldCreatePendingJob() {
        UUID memberId = UUID.randomUUID();

        AnalysisJob job = AnalysisJob.submit(memberId);

        assertThat(job.getId()).isNotNull();
        assertThat(job.getMemberId()).isEqualTo(memberId);
        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        assertThat(job.getResultJson()).isNull();
        assertThat(job.getErrorMessage()).isNull();
    }

    @Test
    void markProcessing_shouldTransitionToProcessing() {
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());

        job.markProcessing();

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
    }

    @Test
    void markCompleted_shouldTransitionToCompletedAndStoreResult() {
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());

        job.markCompleted("{\"score\":0.87}");

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(job.getResultJson()).isEqualTo("{\"score\":0.87}");
    }

    @Test
    void markCompletedWithPartialError_shouldStayCompletedAndStoreResultAndErrorCodeTogether() {
        // 얼굴없음처럼 일부 판독만 정상적으로 비어있는 경우 - 전체 실패(FAILED)가 아니라
        // COMPLETED를 유지하면서 이유도 같이 기록할 수 있어야 한다(2026-09-21).
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());

        job.markCompletedWithPartialError("{\"scamDetection\":{\"score\":0.82}}", "NO_FACE_DETECTED", "얼굴을 찾을 수 없습니다.");

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(job.getResultJson()).isEqualTo("{\"scamDetection\":{\"score\":0.82}}");
        assertThat(job.getErrorCode()).isEqualTo("NO_FACE_DETECTED");
        assertThat(job.getErrorMessage()).isEqualTo("얼굴을 찾을 수 없습니다.");
    }

    @Test
    void markFailed_shouldTransitionToFailedAndStoreErrorCodeAndMessage() {
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());

        job.markFailed("ANALYSIS_FAILED", "탐지 서버 호출 실패");

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(job.getErrorCode()).isEqualTo("ANALYSIS_FAILED");
        assertThat(job.getErrorMessage()).isEqualTo("탐지 서버 호출 실패");
    }
}
