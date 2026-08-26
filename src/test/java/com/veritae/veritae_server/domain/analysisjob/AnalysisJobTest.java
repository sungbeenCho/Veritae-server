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
    void markFailed_shouldTransitionToFailedAndStoreErrorMessage() {
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());

        job.markFailed("탐지 서버 호출 실패");

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("탐지 서버 호출 실패");
    }
}
