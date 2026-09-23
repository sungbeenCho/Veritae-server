package com.veritae.veritae_server.domain.analysisrecord;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnalysisRecordRepository extends JpaRepository<AnalysisRecord, UUID> {

    List<AnalysisRecord> findByStatusIn(List<AnalysisJobStatus> statuses);

    Page<AnalysisRecord> findByMemberIdAndStatusOrderByCreatedAtDesc(UUID memberId, AnalysisJobStatus status, Pageable pageable);

    long countByMemberIdAndStatus(UUID memberId, AnalysisJobStatus status);

    long countByMemberIdAndStatusAndModality(UUID memberId, AnalysisJobStatus status, Modality modality);

    long countByMemberIdAndStatusAndAiScoreGreaterThanEqual(UUID memberId, AnalysisJobStatus status, double aiScore);

    long countByMemberIdAndStatusAndScamScoreGreaterThanEqual(UUID memberId, AnalysisJobStatus status, double scamScore);
}
