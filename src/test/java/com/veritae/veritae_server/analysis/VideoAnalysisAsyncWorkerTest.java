package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.ScamDetectionResult;
import com.veritae.veritae_server.detection.ScamEvidence;
import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.VideoDetectionResult;
import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.VideoDetectionClient;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus;
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
    private AnalysisRecordRepository analysisRecordRepository;

    @Captor
    private ArgumentCaptor<AnalysisRecord> jobCaptor;

    private VideoAnalysisAsyncWorker worker;

    @Test
    void process_withSuccessfulDetection_shouldMarkJobCompletedWithResultJson() {
        // Given
        worker = new VideoAnalysisAsyncWorker(videoDetectionClient, analysisRecordRepository);
        AnalysisRecord job = AnalysisRecord.submit(UUID.randomUUID());
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenReturn(new VideoAnalysisResult(
                        new VideoDetectionResult("dfdc", 0.91, List.of(), "base64png"), null, null));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        verify(analysisRecordRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
        AnalysisRecord savedJob = jobCaptor.getValue();
        assertThat(savedJob.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(savedJob.getResultJson()).contains("\"model\":\"dfdc\"").contains("0.91");
        assertThat(savedJob.getAiScore()).isEqualTo(0.91);
        assertThat(savedJob.getScamScore()).isNull();
    }

    @Test
    void process_whenDetectionThrows_shouldMarkJobFailedWithErrorMessage() {
        // Given
        worker = new VideoAnalysisAsyncWorker(videoDetectionClient, analysisRecordRepository);
        AnalysisRecord job = AnalysisRecord.submit(UUID.randomUUID());
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenThrow(new DetectionServiceException("탐지 서버 호출에 실패했습니다.", null));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then: errorMessage는 탐지 서버의 원문 에러(내부 경로/traceback 가능성)를 그대로 노출하지 않고
        // 일반화된 메시지로 저장되어야 한다.
        verify(analysisRecordRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
        AnalysisRecord savedJob = jobCaptor.getValue();
        assertThat(savedJob.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(savedJob.getErrorCode()).isEqualTo("ANALYSIS_FAILED");
        assertThat(savedJob.getErrorMessage()).isEqualTo("영상 분석 중 오류가 발생했습니다.");
    }

    @Test
    void process_whenNoFaceDetected_shouldMarkJobCompletedWithErrorCodeAndNoAiDetection() {
        // Given: 얼굴 없음은 정상적인 한계지 장애가 아니라서(2026-09-21), 전체 실패가 아니라
        // COMPLETED로 기록하고 이유는 errorCode/errorMessage에 담는다.
        worker = new VideoAnalysisAsyncWorker(videoDetectionClient, analysisRecordRepository);
        AnalysisRecord job = AnalysisRecord.submit(UUID.randomUUID());
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenReturn(new VideoAnalysisResult(null, null, "NO_FACE_DETECTED"));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        verify(analysisRecordRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
        AnalysisRecord savedJob = jobCaptor.getValue();
        assertThat(savedJob.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(savedJob.getErrorCode()).isEqualTo("NO_FACE_DETECTED");
        assertThat(savedJob.getErrorMessage()).isNotBlank();
    }

    @Test
    void process_whenNoFaceDetectedButScamDetectionSucceeded_shouldKeepScamDetectionInResultJson() {
        // Given: AI판독은 얼굴없음으로 못 하지만, 별개로 도는 사기감지는 성공한 경우 -
        // 이 결과를 버리지 않고 살려서 저장하는지가 이번 수정의 핵심이다(2026-09-21).
        worker = new VideoAnalysisAsyncWorker(videoDetectionClient, analysisRecordRepository);
        AnalysisRecord job = AnalysisRecord.submit(UUID.randomUUID());
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));
        var scamDetection = new ScamDetectionResult(
                "lilju", 0.82, List.of(new ScamEvidence("계좌번호를 알려주세요", 0.95)));
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenReturn(new VideoAnalysisResult(null, scamDetection, "NO_FACE_DETECTED"));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        verify(analysisRecordRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
        AnalysisRecord savedJob = jobCaptor.getValue();
        assertThat(savedJob.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(savedJob.getErrorCode()).isEqualTo("NO_FACE_DETECTED");
        assertThat(savedJob.getResultJson()).contains("계좌번호를 알려주세요");
        assertThat(savedJob.getAiScore()).isNull();
        assertThat(savedJob.getScamScore()).isEqualTo(0.82);
    }
}
