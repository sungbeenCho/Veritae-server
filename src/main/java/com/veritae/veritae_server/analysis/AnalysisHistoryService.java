package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.analysisrecord.Modality;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalysisHistoryService {

    // 리포트에서 "탐지됨"으로 셀 점수 기준선. 0~1 확률 중 과반(0.5) 이상을 탐지로 판단한다
    // (docs/superpowers/specs/2026-09-23-analysis-history-report-design.md — 고정값, 추후 조정 가능).
    private static final double RISK_THRESHOLD = 0.5;

    // 목록 조회는 페이지네이션 없이 최신 10건 고정.
    private static final int RECENT_RECORDS_LIMIT = 10;

    private final AnalysisRecordRepository analysisRecordRepository;

    public List<AnalysisRecord> getRecords(UUID memberId) {
        // 정렬은 리포지토리 메서드 이름(OrderByCreatedAtDesc)에 이미 들어있어 Pageable에 Sort를 또 줄 필요가 없다.
        return analysisRecordRepository.findByMemberIdAndStatusOrderByCreatedAtDesc(
                memberId, AnalysisJobStatus.COMPLETED, PageRequest.of(0, RECENT_RECORDS_LIMIT)).getContent();
    }

    // 6번의 count 쿼리가 서로 다른 트랜잭션에서 실행되면 그 사이에 새 기록이 저장됐을 때
    // 합계가 모달리티별 합과 어긋날 수 있다 - 하나의 읽기 트랜잭션으로 묶어 일관된 스냅샷에서 센다.
    @Transactional(readOnly = true)
    public AnalysisReportView getReport(UUID memberId) {
        long total = analysisRecordRepository.countByMemberIdAndStatus(memberId, AnalysisJobStatus.COMPLETED);
        long imageCount = analysisRecordRepository.countByMemberIdAndStatusAndModality(
                memberId, AnalysisJobStatus.COMPLETED, Modality.IMAGE);
        long audioCount = analysisRecordRepository.countByMemberIdAndStatusAndModality(
                memberId, AnalysisJobStatus.COMPLETED, Modality.AUDIO);
        long videoCount = analysisRecordRepository.countByMemberIdAndStatusAndModality(
                memberId, AnalysisJobStatus.COMPLETED, Modality.VIDEO);
        long aiDetectedCount = analysisRecordRepository.countByMemberIdAndStatusAndAiScoreGreaterThanEqual(
                memberId, AnalysisJobStatus.COMPLETED, RISK_THRESHOLD);
        long scamDetectedCount = analysisRecordRepository.countByMemberIdAndStatusAndScamScoreGreaterThanEqual(
                memberId, AnalysisJobStatus.COMPLETED, RISK_THRESHOLD);
        return new AnalysisReportView(total, imageCount, audioCount, videoCount, aiDetectedCount, scamDetectedCount);
    }

    public record AnalysisReportView(
            long totalCount, long imageCount, long audioCount, long videoCount,
            long aiDetectedCount, long scamDetectedCount) {
    }
}
