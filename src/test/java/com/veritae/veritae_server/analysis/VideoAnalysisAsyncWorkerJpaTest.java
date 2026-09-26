package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.VideoDetectionClient;
import com.veritae.veritae_server.detection.VideoDetectionResult;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.media.InMemoryMediaStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VideoAnalysisAsyncWorker 의 "분석 도중 회원이 탈퇴해 기록이 지워져도 되살리지 않는다" 안전장치를 실제
 * JPA/DB(H2)로 확인한다. 단위 테스트(VideoAnalysisAsyncWorkerTest)는 트랜잭션을 흉내만 내므로, 이 장치가
 * 기대는 Hibernate 동작(다른 트랜잭션이 지운 행을 UPDATE 하면 0건 → 낙관적 락 예외)은 여기서만 검증된다.
 * 테스트마다 트랜잭션을 직접 나눠야 해서 @DataJpaTest 의 테스트 단위 트랜잭션은 끈다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VideoAnalysisAsyncWorkerJpaTest {

    @Autowired
    private AnalysisRecordRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private TransactionTemplate newTransaction;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        newTransaction = new TransactionTemplate(transactionManager);
        newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void updatingRecordDeletedByAnotherTransaction_shouldFailInsteadOfRecreatingIt() {
        UUID memberId = UUID.randomUUID();
        AnalysisRecord saved = repository.save(AnalysisRecord.submit(memberId));

        // 조회한 뒤 커밋하기 전에, 다른 트랜잭션(탈퇴)이 그 기록을 지우고 먼저 커밋한다.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            AnalysisRecord record = repository.findById(saved.getId()).orElseThrow();
            record.markProcessing();
            newTransaction.executeWithoutResult(other -> repository.deleteAllByMemberId(memberId));
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(repository.existsById(saved.getId())).isFalse();
    }

    @Test
    void process_whenMemberWithdrawsDuringDetection_shouldNotRecreateRecordNorKeepOriginal() {
        UUID memberId = UUID.randomUUID();
        AnalysisRecord job = repository.save(AnalysisRecord.submit(memberId));
        InMemoryMediaStorage storage = new InMemoryMediaStorage();
        VideoDetectionClient detectionClient = mock(VideoDetectionClient.class);
        when(detectionClient.detectVideo(any(), any(), any())).thenAnswer(invocation -> {
            repository.deleteAllByMemberId(memberId); // 분석하는 동안 탈퇴
            return new VideoAnalysisResult(new VideoDetectionResult("dfdc", 0.91, List.of(), null), null, null);
        });
        VideoAnalysisAsyncWorker worker = new VideoAnalysisAsyncWorker(detectionClient, repository,
                new AnalysisMediaService(storage, repository), transactionTemplate);

        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        assertThat(repository.existsById(job.getId())).isFalse();
        assertThat(storage.objects()).isEmpty();
    }

    @Test
    void process_withSuccessfulDetection_shouldPersistCompletedJobWithOriginal() {
        UUID memberId = UUID.randomUUID();
        AnalysisRecord job = repository.save(AnalysisRecord.submit(memberId));
        InMemoryMediaStorage storage = new InMemoryMediaStorage();
        VideoDetectionClient detectionClient = mock(VideoDetectionClient.class);
        when(detectionClient.detectVideo(any(), any(), any())).thenReturn(
                new VideoAnalysisResult(new VideoDetectionResult("dfdc", 0.91, List.of(), null), null, null));
        VideoAnalysisAsyncWorker worker = new VideoAnalysisAsyncWorker(detectionClient, repository,
                new AnalysisMediaService(storage, repository), transactionTemplate);

        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        AnalysisRecord reloaded = repository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus())
                .isEqualTo(com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus.COMPLETED);
        assertThat(reloaded.getAiScore()).isEqualTo(0.91);
        assertThat(reloaded.getMediaKey()).isEqualTo("media/" + memberId + "/" + job.getId());
        assertThat(storage.objects()).containsKey(reloaded.getMediaKey());
    }
}
