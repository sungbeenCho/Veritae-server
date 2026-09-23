package com.veritae.veritae_server.domain.analysisrecord;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

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
                memberId, AnalysisJobStatus.COMPLETED, PageRequest.of(0, 10, Sort.by("createdAt").descending()));

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
}
