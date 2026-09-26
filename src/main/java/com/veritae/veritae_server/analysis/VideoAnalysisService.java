package com.veritae.veritae_server.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus;
import com.veritae.veritae_server.domain.analysisrecord.Modality;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.UUID;

/**
 * 영상 검증 + job 생성 + (별도 빈 VideoAnalysisAsyncWorker를 통한) 비동기 처리 트리거 +
 * job 조회를 담당한다. 처리 자체를 이 클래스 안에 두지 않고 VideoAnalysisAsyncWorker로
 * 위임하는 이유는 그 클래스의 주석 참고(스프링 @Async self-invocation 함정).
 */
@Service
@RequiredArgsConstructor
public class VideoAnalysisService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("video/mp4", "video/quicktime", "video/x-msvideo");
    private static final long MAX_FILE_SIZE_BYTES = 100L * 1024 * 1024;

    // resultJson은 우리가 직접 쓰고 직접 읽는 순수 내부 저장 포맷이지 iOS로 나가는 응답이 아니다
    // (그건 openapi-generator가 만든 Jackson 3 기반 DTO를 통해 나간다). 이 프로젝트는 Spring Boot 4 +
    // Jackson 3(tools.jackson) 기본 스택이라 com.fasterxml.jackson.databind.ObjectMapper 빈이
    // 자동 등록되지 않으므로, DI 대신 기본 설정 인스턴스를 직접 만들어 쓴다.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AnalysisRecordRepository analysisRecordRepository;
    private final VideoAnalysisAsyncWorker videoAnalysisAsyncWorker;

    public UUID submitVideo(MultipartFile file, UUID memberId) {
        validate(file);
        byte[] videoBytes;
        try {
            videoBytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("업로드된 파일을 읽을 수 없습니다.", e);
        }

        AnalysisRecord record = AnalysisRecord.submit(memberId);
        analysisRecordRepository.save(record);

        videoAnalysisAsyncWorker.process(record.getId(), videoBytes, file.getOriginalFilename(), file.getContentType());
        return record.getId();
    }

    public AnalysisJobView getJob(UUID jobId, UUID requesterId) {
        // 이미지/음성 기록 id도 응답으로 나가므로 그 id가 들어올 수 있다 - 영상 작업이 아니면 없는
        // 작업으로 본다(영상 결과 형식으로 읽으려다 500이 나지 않게).
        AnalysisRecord record = analysisRecordRepository.findById(jobId)
                .filter(r -> r.getMemberId().equals(requesterId))
                .filter(r -> r.getModality() == Modality.VIDEO)
                .orElseThrow(() -> new AnalysisJobNotFoundException(jobId));

        VideoAnalysisResult result = record.getStatus() == AnalysisJobStatus.COMPLETED
                ? readResultJson(record.getResultJson())
                : null;
        // 영상은 접수 순서대로 대기열(AsyncConfig, 먼저 들어온 순서로 꺼냄)에 들어가 2건씩 동시에 처리된다.
        // 나보다 먼저 접수돼 아직 처리를 시작하지 않은(PENDING) 작업 수가 곧 내 앞의 대기 건수다.
        Integer jobsAhead = record.getStatus() == AnalysisJobStatus.PENDING
                ? Math.toIntExact(analysisRecordRepository.countByModalityAndStatusAndCreatedAtBefore(
                        Modality.VIDEO, AnalysisJobStatus.PENDING, record.getCreatedAt()))
                : null;
        return new AnalysisJobView(record.getId(), record.getStatus(), result, record.getErrorCode(),
                record.getErrorMessage(), jobsAhead);
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidVideoFileException("빈 파일은 분석할 수 없습니다.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new InvalidVideoFileException("지원하지 않는 파일 형식입니다: " + contentType);
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidVideoFileException("파일 용량이 100MB를 초과합니다.");
        }
    }

    private VideoAnalysisResult readResultJson(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, VideoAnalysisResult.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
