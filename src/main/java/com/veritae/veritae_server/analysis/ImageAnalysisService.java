package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.detection.DetectionClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ImageAnalysisService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final DetectionClient detectionClient;

    public AiDetectionResult analyzeImage(MultipartFile file) {
        validate(file);
        try {
            return detectionClient.detectImage(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            throw new UncheckedIOException("업로드된 파일을 읽을 수 없습니다.", e);
        }
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidImageFileException("빈 파일은 분석할 수 없습니다.");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new InvalidImageFileException("지원하지 않는 파일 형식입니다: " + file.getContentType());
        }
    }
}
