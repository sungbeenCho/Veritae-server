package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.analysisrecord.Modality;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalysisHistoryService {

    // 리포트에서 "탐지됨"으로 셀 점수 기준선. 0~1 확률 중 과반(0.5) 이상을 탐지로 판단한다
    // (docs/superpowers/specs/2026-09-23-analysis-history-report-design.md — 고정값, 추후 조정 가능).
    private static final double RISK_THRESHOLD = 0.5;

    private final AnalysisRecordRepository analysisRecordRepository;

    public Page<AnalysisRecord> getRecords(UUID memberId, int page, int size) {
        return analysisRecordRepository.findByMemberIdAndStatusOrderByCreatedAtDesc(
                memberId, AnalysisJobStatus.COMPLETED, PageRequest.of(page, size, Sort.by("createdAt").descending()));
    }

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
