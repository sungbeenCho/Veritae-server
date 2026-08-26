package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.domain.analysisjob.AnalysisJob;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobRepository;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaleAnalysisJobCleanerTest {

    @Mock
    private AnalysisJobRepository analysisJobRepository;

    @Captor
    private ArgumentCaptor<List<AnalysisJob>> savedJobsCaptor;

    private StaleAnalysisJobCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new StaleAnalysisJobCleaner(analysisJobRepository);
    }

    @Test
    void cleanUpStaleJobs_withPendingAndProcessingJobs_shouldMarkThemFailed() {
        // Given
        AnalysisJob pendingJob = AnalysisJob.submit(UUID.randomUUID());
        AnalysisJob processingJob = AnalysisJob.submit(UUID.randomUUID());
        processingJob.markProcessing();
        when(analysisJobRepository.findByStatusIn(
                List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.PROCESSING)))
                .thenReturn(List.of(pendingJob, processingJob));

        // When
        cleaner.cleanUpStaleJobs();

        // Then
        verify(analysisJobRepository).saveAll(savedJobsCaptor.capture());
        List<AnalysisJob> savedJobs = savedJobsCaptor.getValue();
        assertThat(savedJobs).hasSize(2);
        assertThat(savedJobs).allSatisfy(job -> {
            assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
            assertThat(job.getErrorMessage()).isEqualTo("서버 재시작으로 처리가 중단됐습니다.");
        });
    }

    @Test
    void cleanUpStaleJobs_withNoStaleJobs_shouldNotSaveAnything() {
        // Given
        when(analysisJobRepository.findByStatusIn(
                List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.PROCESSING)))
                .thenReturn(List.of());

        // When
        cleaner.cleanUpStaleJobs();

        // Then
        verify(analysisJobRepository, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cleanUpStaleJobs_shouldOnlyQueryPendingAndProcessingStatuses() {
        // Given: COMPLETED/FAILED 상태는 findByStatusIn 호출 조건에 포함되지 않아야 한다
        when(analysisJobRepository.findByStatusIn(eq(List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.PROCESSING))))
                .thenReturn(List.of());

        // When
        cleaner.cleanUpStaleJobs();

        // Then
        verify(analysisJobRepository).findByStatusIn(List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.PROCESSING));
    }
}
