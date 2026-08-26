package com.veritae.veritae_server.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJob;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobRepository;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobStatus;
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

    private final AnalysisJobRepository analysisJobRepository;
    private final VideoAnalysisAsyncWorker videoAnalysisAsyncWorker;

    public UUID submitVideo(MultipartFile file, UUID memberId) {
        validate(file);
        byte[] videoBytes;
        try {
            videoBytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("업로드된 파일을 읽을 수 없습니다.", e);
        }

        AnalysisJob job = AnalysisJob.submit(memberId);
        analysisJobRepository.save(job);

        videoAnalysisAsyncWorker.process(job.getId(), videoBytes, file.getOriginalFilename(), file.getContentType());
        return job.getId();
    }

    public AnalysisJobView getJob(UUID jobId, UUID requesterId) {
        AnalysisJob job = analysisJobRepository.findById(jobId)
                .orElseThrow(() -> new AnalysisJobNotFoundException(jobId));
        if (!job.getMemberId().equals(requesterId)) {
            throw new AnalysisJobNotFoundException(jobId);
        }

        AiDetectionResult result = job.getStatus() == AnalysisJobStatus.COMPLETED
                ? readResultJson(job.getResultJson())
                : null;
        return new AnalysisJobView(job.getId(), job.getStatus(), result, job.getErrorMessage());
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidVideoFileException("빈 파일은 분석할 수 없습니다.");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new InvalidVideoFileException("지원하지 않는 파일 형식입니다: " + file.getContentType());
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidVideoFileException("파일 용량이 100MB를 초과합니다.");
        }
    }

    private AiDetectionResult readResultJson(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, AiDetectionResult.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
