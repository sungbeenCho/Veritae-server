package com.veritae.veritae_server.domain.analysisrecord;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AnalysisRecordRepository extends JpaRepository<AnalysisRecord, UUID> {

    List<AnalysisRecord> findByStatusIn(List<AnalysisJobStatus> statuses);

    Page<AnalysisRecord> findByMemberIdAndStatusOrderByCreatedAtDesc(UUID memberId, AnalysisJobStatus status, Pageable pageable);

    long countByMemberIdAndStatus(UUID memberId, AnalysisJobStatus status);

    long countByMemberIdAndStatusAndModality(UUID memberId, AnalysisJobStatus status, Modality modality);

    long countByMemberIdAndStatusAndAiScoreGreaterThanEqual(UUID memberId, AnalysisJobStatus status, double aiScore);

    long countByMemberIdAndStatusAndScamScoreGreaterThanEqual(UUID memberId, AnalysisJobStatus status, double scamScore);

    /** 원본이 남아 있는 회원의 기록(최신순) - 최신 10건 보관 정책 적용용. */
    List<AnalysisRecord> findByMemberIdAndMediaKeyIsNotNullOrderByCreatedAtDesc(UUID memberId);

    /** 영상 대기 순번 - 이 시각보다 먼저 접수돼 아직 대기 중인 작업 수. */
    long countByModalityAndStatusAndCreatedAtBefore(Modality modality, AnalysisJobStatus status, Instant createdAt);

    /**
     * 원본 위치만 지운다(기록은 유지). 엔티티를 불러와 save 하지 않고 UPDATE 한 번으로 처리하는 이유는,
     * 그 사이 회원 탈퇴로 기록이 지워졌을 때 save(merge)가 지워진 기록을 다시 만들어버리기 때문이다.
     * 기록이 없으면 0을 돌려준다.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AnalysisRecord r set r.mediaKey = null where r.id = :id")
    int clearMediaKey(@Param("id") UUID id);

    /** 회원 탈퇴 - 그 회원의 기록을 한 번에 지운다. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AnalysisRecord r where r.memberId = :memberId")
    int deleteAllByMemberId(@Param("memberId") UUID memberId);
}
