package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AudioAnalysisService;
import com.veritae.veritae_server.analysis.ImageAnalysisService;
import com.veritae.veritae_server.analysis.VideoAnalysisService;
import com.veritae.veritae_server.openapi.api.AnalysisApiDelegate;
import com.veritae.veritae_server.openapi.model.AnalysisJobAcceptedResponse;
import com.veritae.veritae_server.openapi.model.AnalysisJobResponse;
import com.veritae.veritae_server.openapi.model.AnalysisRecordListResponse;
import com.veritae.veritae_server.openapi.model.AnalysisReportResponse;
import com.veritae.veritae_server.openapi.model.AudioAnalysisResponse;
import com.veritae.veritae_server.openapi.model.ImageAnalysisResponse;
import com.veritae.veritae_server.security.AuthenticatedMemberResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalysisApiDelegateImpl implements AnalysisApiDelegate {

    private final ImageAnalysisService imageAnalysisService;
    private final AudioAnalysisService audioAnalysisService;
    private final VideoAnalysisService videoAnalysisService;
    private final AuthenticatedMemberResolver authenticatedMemberResolver;
    private final com.veritae.veritae_server.analysis.AnalysisHistoryService analysisHistoryService;

    @Override
    public ResponseEntity<ImageAnalysisResponse> analyzeImage(MultipartFile file) {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        var result = imageAnalysisService.analyzeImage(file, memberId);
        return ResponseEntity.ok(AnalysisApiMapper.toResponse(result));
    }

    @Override
    public ResponseEntity<AudioAnalysisResponse> analyzeAudio(MultipartFile file) {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        var result = audioAnalysisService.analyzeAudio(file, memberId);
        return ResponseEntity.ok(AnalysisApiMapper.toAudioResponse(result));
    }

    @Override
    public ResponseEntity<AnalysisJobAcceptedResponse> analyzeVideo(MultipartFile file) {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        UUID jobId = videoAnalysisService.submitVideo(file, memberId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AnalysisApiMapper.toJobAcceptedResponse(jobId));
    }

    @Override
    public ResponseEntity<AnalysisJobResponse> getAnalysisJob(UUID jobId) {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        var view = videoAnalysisService.getJob(jobId, memberId);
        return ResponseEntity.ok(AnalysisApiMapper.toJobResponse(view));
    }

    @Override
    public ResponseEntity<AnalysisRecordListResponse> getAnalysisRecords(Integer page, Integer size) {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        int resolvedPage = page != null ? page : 0;
        int resolvedSize = size != null ? size : 10;
        var records = analysisHistoryService.getRecords(memberId, resolvedPage, resolvedSize);
        return ResponseEntity.ok(AnalysisApiMapper.toRecordListResponse(records));
    }

    @Override
    public ResponseEntity<AnalysisReportResponse> getAnalysisReport() {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        var report = analysisHistoryService.getReport(memberId);
        return ResponseEntity.ok(AnalysisApiMapper.toReportResponse(report));
    }
}
