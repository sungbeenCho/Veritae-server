package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AnalysisMediaService;
import com.veritae.veritae_server.analysis.AudioAnalysisService;
import com.veritae.veritae_server.analysis.ImageAnalysisService;
import com.veritae.veritae_server.analysis.VideoAnalysisService;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus;
import com.veritae.veritae_server.media.MediaObject;
import com.veritae.veritae_server.openapi.api.AnalysisApiDelegate;
import com.veritae.veritae_server.openapi.model.AnalysisJobAcceptedResponse;
import com.veritae.veritae_server.openapi.model.AnalysisJobResponse;
import com.veritae.veritae_server.openapi.model.AnalysisRecordListResponse;
import com.veritae.veritae_server.openapi.model.AnalysisReportResponse;
import com.veritae.veritae_server.openapi.model.AudioAnalysisResponse;
import com.veritae.veritae_server.openapi.model.ImageAnalysisResponse;
import com.veritae.veritae_server.security.AuthenticatedMemberResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalysisApiDelegateImpl implements AnalysisApiDelegate {

    // 영상 폴링 간격(초). 앱은 이 값이 오면 자체 간격(2~10초) 대신 이 값을 쓴다.
    private static final int POLLING_RETRY_AFTER_SECONDS = 5;

    private final ImageAnalysisService imageAnalysisService;
    private final AudioAnalysisService audioAnalysisService;
    private final VideoAnalysisService videoAnalysisService;
    private final AuthenticatedMemberResolver authenticatedMemberResolver;
    private final com.veritae.veritae_server.analysis.AnalysisHistoryService analysisHistoryService;
    private final AnalysisMediaService analysisMediaService;

    @Override
    public ResponseEntity<ImageAnalysisResponse> analyzeImage(MultipartFile file) {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        var outcome = imageAnalysisService.analyzeImage(file, memberId);
        return ResponseEntity.ok(AnalysisApiMapper.toResponse(outcome));
    }

    @Override
    public ResponseEntity<AudioAnalysisResponse> analyzeAudio(MultipartFile file) {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        var outcome = audioAnalysisService.analyzeAudio(file, memberId);
        return ResponseEntity.ok(AnalysisApiMapper.toAudioResponse(outcome));
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
        var response = ResponseEntity.ok();
        if (view.status() == AnalysisJobStatus.PENDING || view.status() == AnalysisJobStatus.PROCESSING) {
            // 서버가 폴링 간격을 정해준다(RFC 9110 Retry-After, 초 단위).
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(POLLING_RETRY_AFTER_SECONDS));
        }
        return response.body(AnalysisApiMapper.toJobResponse(view));
    }

    @Override
    public ResponseEntity<Resource> getAnalysisRecordMedia(UUID id, String range) {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        MediaObject media = analysisMediaService.openOriginal(id, memberId, range);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(media.contentType() != null
                ? MediaType.parseMediaType(media.contentType())
                : MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(media.contentLength());
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        // 파일로 내려받는 응답임을 알린다 - Swagger UI 가 영상 바이트를 글자로 찍지 않고 다운로드 링크를
        // 보여준다. 앱(URLSession/AVPlayer)은 이 헤더를 쓰지 않아 동작이 바뀌지 않는다. 원본 파일 이름은
        // 보관하지 않으므로 "기록id.확장자"로 이름을 붙인다.
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(id + "." + mediaFileExtension(media.contentType()))
                .build());
        if (media.partial()) {
            headers.set(HttpHeaders.CONTENT_RANGE, media.contentRange());
        }
        // 저장소(R2)에서 받는 스트림을 그대로 흘려보낸다 - 영상(최대 100MB)을 메모리에 다 올리지 않는다.
        // InputStreamResource 는 Spring 이 Range 를 다시 처리하지 않는 타입이라, R2 가 잘라준 범위가 그대로 나간다.
        return ResponseEntity.status(media.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .headers(headers)
                .body(new InputStreamResource(media.content()));
    }

    // 업로드 때 허용하는 형식(이미지/음성/영상 서비스의 ALLOWED_CONTENT_TYPES)별 확장자.
    static String mediaFileExtension(String contentType) {
        if (contentType == null) {
            return "bin";
        }
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mpeg" -> "mp3";
            case "audio/mp4" -> "m4a";
            case "audio/aac" -> "aac";
            case "video/mp4" -> "mp4";
            case "video/quicktime" -> "mov";
            case "video/x-msvideo" -> "avi";
            default -> "bin";
        };
    }

    @Override
    public ResponseEntity<AnalysisRecordListResponse> getAnalysisRecords() {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        var records = analysisHistoryService.getRecords(memberId);
        return ResponseEntity.ok(AnalysisApiMapper.toRecordListResponse(records));
    }

    @Override
    public ResponseEntity<AnalysisReportResponse> getAnalysisReport() {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        var report = analysisHistoryService.getReport(memberId);
        return ResponseEntity.ok(AnalysisApiMapper.toReportResponse(report));
    }
}
