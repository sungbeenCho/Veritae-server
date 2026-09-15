package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobStatus;

import java.util.UUID;

/**
 * AnalysisJob 조회 결과. result 는 status가 COMPLETED일 때만 채워진다(resultJson을
 * VideoAnalysisService가 역직렬화해서 넣어준다 - AnalysisJob 엔티티 자체는 JSON
 * 역직렬화 책임을 모른다).
 */
public record AnalysisJobView(
        UUID jobId, AnalysisJobStatus status, VideoAnalysisResult result, String errorCode, String errorMessage) {
}
