package com.veritae.veritae_server.domain.analysisrecord;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class AnalysisRecordRepositoryTest {

    @org.springframework.beans.factory.annotation.Autowired
    private AnalysisRecordRepository repository;

    @Test
    void findByMemberIdAndStatusOrderByCreatedAtDesc_shouldReturnOnlyThatMembersCompletedRecordsNewestFirst()
            throws InterruptedException {
        UUID memberId = UUID.randomUUID();
        UUID otherMemberId = UUID.randomUUID();
        AnalysisRecord older = repository.save(AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.1, null));
        // createdAt은 Instant.now()를 그대로 쓰므로, 같은 밀리초에 저장되면 정렬 순서 검증이
        // 우연에 의존하게 된다. 두 저장 사이에 최소한의 지연을 둬서 older가 실제로 더 이전
        // 타임스탬프를 갖도록 보장한다.
        Thread.sleep(5);
        AnalysisRecord newer = repository.save(AnalysisRecord.completedSync(memberId, Modality.AUDIO, "{}", 0.2, null));
        repository.save(AnalysisRecord.submit(memberId)); // PENDING, 목록에서 제외돼야 함
        repository.save(AnalysisRecord.completedSync(otherMemberId, Modality.IMAGE, "{}", 0.3, null));

        Page<AnalysisRecord> page = repository.findByMemberIdAndStatusOrderByCreatedAtDesc(
                memberId, AnalysisJobStatus.COMPLETED, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allMatch(r -> r.getMemberId().equals(memberId));
        assertThat(page.getContent()).allMatch(r -> r.getStatus() == AnalysisJobStatus.COMPLETED);
        // 핵심 검증: OrderByCreatedAtDesc가 실제로 최신순 정렬을 하는지 내용으로 확인
        assertThat(page.getContent().get(0).getId()).isEqualTo(newer.getId());
        assertThat(page.getContent().get(1).getId()).isEqualTo(older.getId());
    }

    @Test
    void countQueries_shouldAggregateByModalityAndScoreThreshold() {
        UUID memberId = UUID.randomUUID();
        repository.save(AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.9, null));
        repository.save(AnalysisRecord.completedSync(memberId, Modality.AUDIO, "{}", 0.1, 0.6));
        repository.save(AnalysisRecord.completedSync(memberId, Modality.VIDEO, "{}", null, 0.2));

        assertThat(repository.countByMemberIdAndStatus(memberId, AnalysisJobStatus.COMPLETED)).isEqualTo(3);
        assertThat(repository.countByMemberIdAndStatusAndModality(memberId, AnalysisJobStatus.COMPLETED, Modality.IMAGE)).isEqualTo(1);
        assertThat(repository.countByMemberIdAndStatusAndAiScoreGreaterThanEqual(memberId, AnalysisJobStatus.COMPLETED, 0.5)).isEqualTo(1);
        assertThat(repository.countByMemberIdAndStatusAndScamScoreGreaterThanEqual(memberId, AnalysisJobStatus.COMPLETED, 0.5)).isEqualTo(1);
    }

    @Test
    void findByMemberIdAndMediaKeyIsNotNullOrderByCreatedAtDesc_shouldReturnOnlyThatMembersRecordsWithMediaNewestFirst()
            throws InterruptedException {
        UUID memberId = UUID.randomUUID();
        AnalysisRecord older = withMedia(AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.1, null));
        repository.save(older);
        Thread.sleep(5);
        AnalysisRecord newer = withMedia(AnalysisRecord.completedSync(memberId, Modality.AUDIO, "{}", 0.2, null));
        repository.save(newer);
        repository.save(AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.3, null)); // 원본 없음
        repository.save(withMedia(AnalysisRecord.completedSync(UUID.randomUUID(), Modality.IMAGE, "{}", 0.4, null)));

        List<AnalysisRecord> records = repository.findByMemberIdAndMediaKeyIsNotNullOrderByCreatedAtDesc(memberId);

        assertThat(records).extracting(AnalysisRecord::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void clearMediaKey_shouldRemoveOnlyTheMediaKeyAndKeepTheRecord() {
        AnalysisRecord record = repository.save(
                withMedia(AnalysisRecord.completedSync(UUID.randomUUID(), Modality.IMAGE, "{}", 0.1, null)));

        int updated = repository.clearMediaKey(record.getId());

        assertThat(updated).isEqualTo(1);
        AnalysisRecord reloaded = repository.findById(record.getId()).orElseThrow();
        assertThat(reloaded.getMediaKey()).isNull();
        assertThat(reloaded.getAiScore()).isEqualTo(0.1);
    }

    @Test
    void countByModalityAndStatusAndCreatedAtBefore_shouldCountOnlyEarlierPendingVideoJobs() throws InterruptedException {
        repository.save(AnalysisRecord.submit(UUID.randomUUID()));
        Thread.sleep(5);
        repository.save(AnalysisRecord.submit(UUID.randomUUID()));
        Thread.sleep(5);
        AnalysisRecord mine = repository.save(AnalysisRecord.submit(UUID.randomUUID()));
        Thread.sleep(5);
        repository.save(AnalysisRecord.submit(UUID.randomUUID())); // 나보다 늦게 들어온 작업

        // mine 자신은 세지 않아야 한다 - 메모리의 createdAt과 DB에 저장된 createdAt의 정밀도가
        // 다르면(나노초 vs 마이크로초) 자기 자신이 "더 먼저 들어온 작업"으로 잘못 세어진다.
        long ahead = repository.countByModalityAndStatusAndCreatedAtBefore(
                Modality.VIDEO, AnalysisJobStatus.PENDING, mine.getCreatedAt());

        assertThat(ahead).isEqualTo(2);
    }

    @Test
    void deleteAllByMemberId_shouldDeleteOnlyThatMembersRecords() {
        UUID memberId = UUID.randomUUID();
        UUID otherMemberId = UUID.randomUUID();
        repository.save(AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.1, null));
        repository.save(AnalysisRecord.submit(memberId));
        AnalysisRecord others = repository.save(AnalysisRecord.completedSync(otherMemberId, Modality.IMAGE, "{}", 0.1, null));

        int deleted = repository.deleteAllByMemberId(memberId);

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.findAll()).extracting(AnalysisRecord::getId).containsExactly(others.getId());
    }

    private static AnalysisRecord withMedia(AnalysisRecord record) {
        record.attachMedia("media/" + record.getMemberId() + "/" + record.getId());
        return record;
    }
}
