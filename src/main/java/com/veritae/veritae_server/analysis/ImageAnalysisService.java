package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.DetectionClient;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.Modality;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageAnalysisService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    // resultJson은 우리가 직접 쓰고 직접 읽는 순수 내부 저장 포맷 — VideoAnalysisAsyncWorker와 동일한
    // 이유로(Jackson 3 스택에서 ObjectMapper 빈 자동등록 안 됨) DI 대신 직접 인스턴스를 만들어 쓴다.
    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final DetectionClient detectionClient;
    private final AnalysisMediaService analysisMediaService;

    public AnalysisOutcome<ImageAnalysisResult> analyzeImage(MultipartFile file, UUID memberId) {
        validate(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("업로드된 파일을 읽을 수 없습니다.", e);
        }
        ImageAnalysisResult result = detectionClient.detectImage(bytes, file.getOriginalFilename(), file.getContentType());

        Double aiScore = result.aiDetection() != null ? result.aiDetection().score() : null;
        Double scamScore = result.scamDetection() != null ? result.scamDetection().score() : null;
        AnalysisRecord record = AnalysisRecord.completedSync(
                memberId, Modality.IMAGE, writeResultJson(result), aiScore, scamScore);
        analysisMediaService.saveRecordWithOriginal(record, bytes, file.getContentType());

        return new AnalysisOutcome<>(record.getId(), result);
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidImageFileException("빈 파일은 분석할 수 없습니다.");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new InvalidImageFileException("지원하지 않는 파일 형식입니다: " + file.getContentType());
        }
    }

    private String writeResultJson(ImageAnalysisResult result) {
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
