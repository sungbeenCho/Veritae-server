package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.AudioAnalysisResult;
import com.veritae.veritae_server.detection.AudioDetectionClient;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
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
public class AudioAnalysisService {

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp4", "audio/aac");
    private static final long MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024;

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final AudioDetectionClient audioDetectionClient;
    private final AnalysisRecordRepository analysisRecordRepository;

    public AudioAnalysisResult analyzeAudio(MultipartFile file, UUID memberId) {
        validate(file);
        AudioAnalysisResult result;
        try {
            result = audioDetectionClient.detectAudio(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            throw new UncheckedIOException("업로드된 파일을 읽을 수 없습니다.", e);
        }

        Double aiScore = result.aiDetection() != null ? result.aiDetection().score() : null;
        Double scamScore = result.scamDetection() != null ? result.scamDetection().score() : null;
        analysisRecordRepository.save(AnalysisRecord.completedSync(
                memberId, Modality.AUDIO, writeResultJson(result), aiScore, scamScore));

        return result;
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidAudioFileException("빈 파일은 분석할 수 없습니다.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new InvalidAudioFileException("지원하지 않는 파일 형식입니다: " + contentType);
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidAudioFileException("파일 용량이 25MB를 초과합니다.");
        }
    }

    private String writeResultJson(AudioAnalysisResult result) {
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
