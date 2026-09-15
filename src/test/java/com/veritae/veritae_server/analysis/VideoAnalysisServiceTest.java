package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.VideoDetectionResult;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJob;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobRepository;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoAnalysisServiceTest {

    @Mock
    private AnalysisJobRepository analysisJobRepository;

    @Mock
    private VideoAnalysisAsyncWorker videoAnalysisAsyncWorker;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private VideoAnalysisService videoAnalysisService;

    @BeforeEach
    void setUp() {
        videoAnalysisService = new VideoAnalysisService(analysisJobRepository, videoAnalysisAsyncWorker);
    }

    @Test
    void submitVideo_withValidMp4_shouldCreatePendingJobAndTriggerAsyncWorker() throws Exception {
        // Given
        var file = new MockMultipartFile("file", "test.mp4", "video/mp4", "fake-bytes".getBytes());
        UUID memberId = UUID.randomUUID();

        // When
        UUID jobId = videoAnalysisService.submitVideo(file, memberId);

        // Then
        assertThat(jobId).isNotNull();
        verify(analysisJobRepository).save(any(AnalysisJob.class));
        verify(videoAnalysisAsyncWorker).process(org.mockito.ArgumentMatchers.eq(jobId), any(), org.mockito.ArgumentMatchers.eq("test.mp4"), org.mockito.ArgumentMatchers.eq("video/mp4"));
    }

    @Test
    void submitVideo_withEmptyFile_shouldThrowInvalidVideoFileException() {
        // Given
        var file = new MockMultipartFile("file", "test.mp4", "video/mp4", new byte[0]);

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.submitVideo(file, UUID.randomUUID()))
                .isInstanceOf(InvalidVideoFileException.class);
        verifyNoInteractions(analysisJobRepository, videoAnalysisAsyncWorker);
    }

    @Test
    void submitVideo_withUnsupportedContentType_shouldThrowInvalidVideoFileException() {
        // Given
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "not-a-video".getBytes());

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.submitVideo(file, UUID.randomUUID()))
                .isInstanceOf(InvalidVideoFileException.class);
        verifyNoInteractions(analysisJobRepository, videoAnalysisAsyncWorker);
    }

    @Test
    void submitVideo_withNullContentType_shouldThrowInvalidVideoFileException() {
        // Given
        var file = new MockMultipartFile("file", "test.mp4", null, "not-a-video".getBytes());

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.submitVideo(file, UUID.randomUUID()))
                .isInstanceOf(InvalidVideoFileException.class);
        verifyNoInteractions(analysisJobRepository, videoAnalysisAsyncWorker);
    }

    @Test
    void submitVideo_withFileLargerThan100Mb_shouldThrowInvalidVideoFileException() {
        // Given
        byte[] tooLarge = new byte[101 * 1024 * 1024];
        var file = new MockMultipartFile("file", "test.mp4", "video/mp4", tooLarge);

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.submitVideo(file, UUID.randomUUID()))
                .isInstanceOf(InvalidVideoFileException.class);
        verifyNoInteractions(analysisJobRepository, videoAnalysisAsyncWorker);
    }

    @Test
    void getJob_withOwnCompletedJob_shouldReturnViewWithDeserializedResult() throws Exception {
        // Given
        UUID memberId = UUID.randomUUID();
        AnalysisJob job = AnalysisJob.submit(memberId);
        job.markCompleted(objectMapper.writeValueAsString(
                new VideoAnalysisResult(new VideoDetectionResult("dfdc", 0.91, List.of(), null), null)));
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        // When
        AnalysisJobView view = videoAnalysisService.getJob(job.getId(), memberId);

        // Then
        assertThat(view.status()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(view.result().aiDetection().model()).isEqualTo("dfdc");
        assertThat(view.result().aiDetection().score()).isEqualTo(0.91);
        assertThat(view.errorMessage()).isNull();
    }

    @Test
    void getJob_withPendingJob_shouldReturnViewWithNullResult() {
        // Given
        UUID memberId = UUID.randomUUID();
        AnalysisJob job = AnalysisJob.submit(memberId);
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        // When
        AnalysisJobView view = videoAnalysisService.getJob(job.getId(), memberId);

        // Then
        assertThat(view.status()).isEqualTo(AnalysisJobStatus.PENDING);
        assertThat(view.result()).isNull();
    }

    @Test
    void getJob_withNonExistentJob_shouldThrowAnalysisJobNotFoundException() {
        // Given
        UUID jobId = UUID.randomUUID();
        when(analysisJobRepository.findById(jobId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.getJob(jobId, UUID.randomUUID()))
                .isInstanceOf(AnalysisJobNotFoundException.class);
    }

    @Test
    void getJob_withAnotherMembersJob_shouldThrowAnalysisJobNotFoundException() {
        // Given
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        // When / Then: 요청자가 소유자가 아님
        assertThatThrownBy(() -> videoAnalysisService.getJob(job.getId(), UUID.randomUUID()))
                .isInstanceOf(AnalysisJobNotFoundException.class);
    }
}
