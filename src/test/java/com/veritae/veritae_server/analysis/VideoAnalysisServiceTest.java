package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.VideoDetectionResult;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus;
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
    private AnalysisRecordRepository analysisRecordRepository;

    @Mock
    private VideoAnalysisAsyncWorker videoAnalysisAsyncWorker;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private VideoAnalysisService videoAnalysisService;

    @BeforeEach
    void setUp() {
        videoAnalysisService = new VideoAnalysisService(analysisRecordRepository, videoAnalysisAsyncWorker);
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
        verify(analysisRecordRepository).save(any(AnalysisRecord.class));
        verify(videoAnalysisAsyncWorker).process(org.mockito.ArgumentMatchers.eq(jobId), any(), org.mockito.ArgumentMatchers.eq("test.mp4"), org.mockito.ArgumentMatchers.eq("video/mp4"));
    }

    @Test
    void submitVideo_withEmptyFile_shouldThrowInvalidVideoFileException() {
        // Given
        var file = new MockMultipartFile("file", "test.mp4", "video/mp4", new byte[0]);

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.submitVideo(file, UUID.randomUUID()))
                .isInstanceOf(InvalidVideoFileException.class);
        verifyNoInteractions(analysisRecordRepository, videoAnalysisAsyncWorker);
    }

    @Test
    void submitVideo_withUnsupportedContentType_shouldThrowInvalidVideoFileException() {
        // Given
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "not-a-video".getBytes());

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.submitVideo(file, UUID.randomUUID()))
                .isInstanceOf(InvalidVideoFileException.class);
        verifyNoInteractions(analysisRecordRepository, videoAnalysisAsyncWorker);
    }

    @Test
    void submitVideo_withNullContentType_shouldThrowInvalidVideoFileException() {
        // Given
        var file = new MockMultipartFile("file", "test.mp4", null, "not-a-video".getBytes());

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.submitVideo(file, UUID.randomUUID()))
                .isInstanceOf(InvalidVideoFileException.class);
        verifyNoInteractions(analysisRecordRepository, videoAnalysisAsyncWorker);
    }

    @Test
    void submitVideo_withFileLargerThan100Mb_shouldThrowInvalidVideoFileException() {
        // Given
        byte[] tooLarge = new byte[101 * 1024 * 1024];
        var file = new MockMultipartFile("file", "test.mp4", "video/mp4", tooLarge);

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.submitVideo(file, UUID.randomUUID()))
                .isInstanceOf(InvalidVideoFileException.class);
        verifyNoInteractions(analysisRecordRepository, videoAnalysisAsyncWorker);
    }

    @Test
    void getJob_withOwnCompletedJob_shouldReturnViewWithDeserializedResult() throws Exception {
        // Given
        UUID memberId = UUID.randomUUID();
        AnalysisRecord job = AnalysisRecord.submit(memberId);
        job.markCompleted(objectMapper.writeValueAsString(
                new VideoAnalysisResult(new VideoDetectionResult("dfdc", 0.91, List.of(), null), null, null)), 0.91, null);
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));

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
        AnalysisRecord job = AnalysisRecord.submit(memberId);
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));

        // When
        AnalysisJobView view = videoAnalysisService.getJob(job.getId(), memberId);

        // Then
        assertThat(view.status()).isEqualTo(AnalysisJobStatus.PENDING);
        assertThat(view.result()).isNull();
    }

    @Test
    void getJob_withPendingJob_shouldReportHowManyEarlierJobsAreStillWaiting() {
        // Given
        UUID memberId = UUID.randomUUID();
        AnalysisRecord job = AnalysisRecord.submit(memberId);
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(analysisRecordRepository.countByModalityAndStatusAndCreatedAtBefore(
                com.veritae.veritae_server.domain.analysisrecord.Modality.VIDEO, AnalysisJobStatus.PENDING, job.getCreatedAt()))
                .thenReturn(3L);

        // When
        AnalysisJobView view = videoAnalysisService.getJob(job.getId(), memberId);

        // Then
        assertThat(view.jobsAhead()).isEqualTo(3);
    }

    @Test
    void getJob_withProcessingJob_shouldNotReportJobsAhead() {
        // Given
        UUID memberId = UUID.randomUUID();
        AnalysisRecord job = AnalysisRecord.submit(memberId);
        job.markProcessing();
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));

        // When
        AnalysisJobView view = videoAnalysisService.getJob(job.getId(), memberId);

        // Then
        assertThat(view.jobsAhead()).isNull();
    }

    @Test
    void getJob_withImageRecordId_shouldThrowAnalysisJobNotFoundException() {
        // Given: 이미지/음성 응답에도 기록 id가 나가므로, 그 id로 영상 폴링을 호출하는 경우 -
        // 영상 결과 형식으로 읽으려다 500이 나면 안 되고 404여야 한다.
        UUID memberId = UUID.randomUUID();
        AnalysisRecord imageRecord = AnalysisRecord.completedSync(
                memberId, com.veritae.veritae_server.domain.analysisrecord.Modality.IMAGE,
                "{\"aiDetection\":{\"model\":\"spai\",\"score\":0.1}}", 0.1, null);
        when(analysisRecordRepository.findById(imageRecord.getId())).thenReturn(Optional.of(imageRecord));

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.getJob(imageRecord.getId(), memberId))
                .isInstanceOf(AnalysisJobNotFoundException.class);
    }

    @Test
    void getJob_withNonExistentJob_shouldThrowAnalysisJobNotFoundException() {
        // Given
        UUID jobId = UUID.randomUUID();
        when(analysisRecordRepository.findById(jobId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.getJob(jobId, UUID.randomUUID()))
                .isInstanceOf(AnalysisJobNotFoundException.class);
    }

    @Test
    void getJob_withAnotherMembersJob_shouldThrowAnalysisJobNotFoundException() {
        // Given
        AnalysisRecord job = AnalysisRecord.submit(UUID.randomUUID());
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));

        // When / Then: 요청자가 소유자가 아님
        assertThatThrownBy(() -> videoAnalysisService.getJob(job.getId(), UUID.randomUUID()))
                .isInstanceOf(AnalysisJobNotFoundException.class);
    }
}
