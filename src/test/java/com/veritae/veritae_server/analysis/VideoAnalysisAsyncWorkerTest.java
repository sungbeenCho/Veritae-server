package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.ScamDetectionResult;
import com.veritae.veritae_server.detection.ScamEvidence;
import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.VideoDetectionClient;
import com.veritae.veritae_server.detection.VideoDetectionResult;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.media.InMemoryMediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoAnalysisAsyncWorkerTest {

    @Mock
    private VideoDetectionClient videoDetectionClient;

    @Mock
    private AnalysisRecordRepository analysisRecordRepository;

    private InMemoryMediaStorage mediaStorage;

    private VideoAnalysisAsyncWorker worker;

    @BeforeEach
    void setUp() {
        mediaStorage = new InMemoryMediaStorage();
        // 트랜잭션 경계 자체는 JPA 없이 검증할 수 없으므로, 여기서는 아무것도 하지 않는 트랜잭션
        // 매니저로 콜백만 실행시키고 "조회한 엔티티를 트랜잭션 안에서 바꾼다"는 동작을 확인한다.
        worker = new VideoAnalysisAsyncWorker(videoDetectionClient, analysisRecordRepository,
                new AnalysisMediaService(mediaStorage, analysisRecordRepository),
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    @Test
    void process_withSuccessfulDetection_shouldMarkJobCompletedWithResultJson() {
        // Given
        AnalysisRecord job = AnalysisRecord.submit(UUID.randomUUID());
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenReturn(new VideoAnalysisResult(
                        new VideoDetectionResult("dfdc", 0.91, List.of(), "base64png"), null, null));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(job.getResultJson()).contains("\"model\":\"dfdc\"").contains("0.91");
        assertThat(job.getAiScore()).isEqualTo(0.91);
        assertThat(job.getScamScore()).isNull();
    }

    @Test
    void process_withSuccessfulDetection_shouldStoreOriginalAndAttachItToJob() {
        // Given
        UUID memberId = UUID.randomUUID();
        AnalysisRecord job = AnalysisRecord.submit(memberId);
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenReturn(new VideoAnalysisResult(
                        new VideoDetectionResult("dfdc", 0.91, List.of(), null), null, null));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        assertThat(job.getMediaKey()).isEqualTo("media/" + memberId + "/" + job.getId());
        assertThat(mediaStorage.objects().get(job.getMediaKey())).isEqualTo("fake-bytes".getBytes());
        verify(analysisRecordRepository).findByMemberIdAndMediaKeyIsNotNullOrderByCreatedAtDesc(memberId);
    }

    @Test
    void process_whenDetectionThrows_shouldMarkJobFailedWithoutStoringOriginal() {
        // Given
        AnalysisRecord job = AnalysisRecord.submit(UUID.randomUUID());
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenThrow(new DetectionServiceException("탐지 서버 호출에 실패했습니다.", null));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then: errorMessage는 탐지 서버의 원문 에러(내부 경로/traceback 가능성)를 그대로 노출하지 않고
        // 일반화된 메시지로 저장되어야 한다. 실패한 분석은 기록 목록에 안 나오므로 원본도 보관하지 않는다.
        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(job.getErrorCode()).isEqualTo("ANALYSIS_FAILED");
        assertThat(job.getErrorMessage()).isEqualTo("영상 분석 중 오류가 발생했습니다.");
        assertThat(job.getMediaKey()).isNull();
        assertThat(mediaStorage.objects()).isEmpty();
    }

    @Test
    void process_whenNoFaceDetected_shouldMarkJobCompletedWithErrorCodeAndNoAiDetection() {
        // Given: 얼굴 없음은 정상적인 한계지 장애가 아니라서(2026-09-21), 전체 실패가 아니라
        // COMPLETED로 기록하고 이유는 errorCode/errorMessage에 담는다.
        AnalysisRecord job = AnalysisRecord.submit(UUID.randomUUID());
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenReturn(new VideoAnalysisResult(null, null, "NO_FACE_DETECTED"));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(job.getErrorCode()).isEqualTo("NO_FACE_DETECTED");
        assertThat(job.getErrorMessage()).isNotBlank();
        assertThat(job.getMediaKey()).isNotNull();
    }

    @Test
    void process_whenNoFaceDetectedButScamDetectionSucceeded_shouldKeepScamDetectionInResultJson() {
        // Given: AI판독은 얼굴없음으로 못 하지만, 별개로 도는 사기감지는 성공한 경우 -
        // 이 결과를 버리지 않고 살려서 저장하는지가 핵심이다(2026-09-21).
        AnalysisRecord job = AnalysisRecord.submit(UUID.randomUUID());
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));
        var scamDetection = new ScamDetectionResult(
                "lilju", 0.82, List.of(new ScamEvidence("계좌번호를 알려주세요", 0.95)));
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenReturn(new VideoAnalysisResult(null, scamDetection, "NO_FACE_DETECTED"));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(job.getErrorCode()).isEqualTo("NO_FACE_DETECTED");
        assertThat(job.getResultJson()).contains("계좌번호를 알려주세요");
        assertThat(job.getAiScore()).isNull();
        assertThat(job.getScamScore()).isEqualTo(0.82);
    }

    @Test
    void process_whenJobDeletedBeforeProcessingStarts_shouldSkipDetection() {
        // Given: 대기 중에 회원이 탈퇴해 기록이 이미 지워진 경우
        UUID jobId = UUID.randomUUID();
        when(analysisRecordRepository.findById(jobId)).thenReturn(Optional.empty());

        // When
        worker.process(jobId, "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        verifyNoInteractions(videoDetectionClient);
        assertThat(mediaStorage.objects()).isEmpty();
    }

    @Test
    void process_whenJobDeletedRightAsProcessingStarts_shouldStopQuietlyWithoutDetection() {
        // Given: 처리 시작(PROCESSING 표시) 트랜잭션의 조회~커밋 사이에 탈퇴로 기록이 지워진 경우 -
        // 커밋 시 UPDATE 가 0건이 되어 낙관적 락 예외가 난다. 이것도 "기록 없음"으로 보고 조용히 멈춰야 한다
        // (예외가 @Async 밖으로 나가 오류 로그로 남으면 안 됨).
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        org.mockito.Mockito.doThrow(new org.springframework.dao.OptimisticLockingFailureException("0 rows"))
                .when(transactionManager).commit(any());
        worker = new VideoAnalysisAsyncWorker(videoDetectionClient, analysisRecordRepository,
                new AnalysisMediaService(mediaStorage, analysisRecordRepository),
                new TransactionTemplate(transactionManager));
        AnalysisRecord job = AnalysisRecord.submit(UUID.randomUUID());
        when(analysisRecordRepository.findById(job.getId())).thenReturn(Optional.of(job));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        verifyNoInteractions(videoDetectionClient);
        assertThat(mediaStorage.objects()).isEmpty();
    }

    @Test
    void process_whenJobDeletedDuringDetection_shouldNotRecreateItAndShouldRemoveUploadedOriginal() {
        // Given: 분석하는 동안 회원이 탈퇴해 기록이 지워진 경우 - 결과를 저장하면서 지워진 기록을
        // 되살리면 안 되고, 방금 올린 원본도 남기면 안 된다.
        AnalysisRecord job = AnalysisRecord.submit(UUID.randomUUID());
        when(analysisRecordRepository.findById(job.getId()))
                .thenReturn(Optional.of(job))      // 처리 시작 시점
                .thenReturn(Optional.empty());     // 결과 저장 시점
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenReturn(new VideoAnalysisResult(
                        new VideoDetectionResult("dfdc", 0.91, List.of(), null), null, null));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        verify(analysisRecordRepository, never()).save(any());
        assertThat(mediaStorage.objects()).isEmpty();
        verify(analysisRecordRepository, never()).findByMemberIdAndMediaKeyIsNotNullOrderByCreatedAtDesc(any());
    }
}
