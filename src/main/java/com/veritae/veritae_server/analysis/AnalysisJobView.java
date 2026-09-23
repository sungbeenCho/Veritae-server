package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus;

import java.util.UUID;

/**
 * 영상 분석 작업(GET /api/v1/analysis/jobs/{jobId}) 폴링 전용 조회 결과 - 이력 목록은
 * 이 타입이 아니라 AnalysisRecordSummary를 쓴다. result 는 status가 COMPLETED일 때만
 * 채워진다(AnalysisRecord 엔티티에 저장된 resultJson을 VideoAnalysisService가 역직렬화해서
 * 넣어준다 - 엔티티 자체는 JSON 역직렬화 책임을 모른다).
 */
public record AnalysisJobView(
        UUID jobId, AnalysisJobStatus status, VideoAnalysisResult result, String errorCode, String errorMessage) {
}
