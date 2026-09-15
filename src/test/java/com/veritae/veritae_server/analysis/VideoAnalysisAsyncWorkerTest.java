package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.VideoDetectionResult;
import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.NoFaceDetectedException;
import com.veritae.veritae_server.detection.VideoDetectionClient;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJob;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobRepository;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoAnalysisAsyncWorkerTest {

    @Mock
    private VideoDetectionClient videoDetectionClient;

    @Mock
    private AnalysisJobRepository analysisJobRepository;

    @Captor
    private ArgumentCaptor<AnalysisJob> jobCaptor;

    private VideoAnalysisAsyncWorker worker;

    @Test
    void process_withSuccessfulDetection_shouldMarkJobCompletedWithResultJson() {
        // Given
        worker = new VideoAnalysisAsyncWorker(videoDetectionClient, analysisJobRepository);
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenReturn(new VideoAnalysisResult(
                        new VideoDetectionResult("dfdc", 0.91, List.of(), "base64png"), null));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        verify(analysisJobRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
        AnalysisJob savedJob = jobCaptor.getValue();
        assertThat(savedJob.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(savedJob.getResultJson()).contains("\"model\":\"dfdc\"").contains("0.91");
    }

    @Test
    void process_whenDetectionThrows_shouldMarkJobFailedWithErrorMessage() {
        // Given
        worker = new VideoAnalysisAsyncWorker(videoDetectionClient, analysisJobRepository);
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenThrow(new DetectionServiceException("탐지 서버 호출에 실패했습니다.", null));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then: errorMessage는 탐지 서버의 원문 에러(내부 경로/traceback 가능성)를 그대로 노출하지 않고
        // 일반화된 메시지로 저장되어야 한다.
        verify(analysisJobRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
        AnalysisJob savedJob = jobCaptor.getValue();
        assertThat(savedJob.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(savedJob.getErrorCode()).isEqualTo("ANALYSIS_FAILED");
        assertThat(savedJob.getErrorMessage()).isEqualTo("영상 분석 중 오류가 발생했습니다.");
    }

    @Test
    void process_whenNoFaceDetected_shouldMarkJobFailedWithSpecificErrorMessage() {
        // Given: 얼굴 없음은 정상적인 사용자 케이스라, 진짜 장애용 일반 메시지가 아니라
        // 원인을 알려주는 구체적인 메시지가 나가야 한다(2026-08-27).
        worker = new VideoAnalysisAsyncWorker(videoDetectionClient, analysisJobRepository);
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenThrow(new NoFaceDetectedException());

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        verify(analysisJobRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
        AnalysisJob savedJob = jobCaptor.getValue();
        assertThat(savedJob.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(savedJob.getErrorCode()).isEqualTo("NO_FACE_DETECTED");
        assertThat(savedJob.getErrorMessage())
                .isEqualTo("영상에서 얼굴을 찾을 수 없습니다. 얼굴이 잘 보이는 영상으로 다시 시도해주세요.");
    }
}
