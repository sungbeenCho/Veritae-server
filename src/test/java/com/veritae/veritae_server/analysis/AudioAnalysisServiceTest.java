package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.AudioAnalysisResult;
import com.veritae.veritae_server.detection.AudioDetectionClient;
import com.veritae.veritae_server.detection.AudioDetectionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AudioAnalysisServiceTest {

    @Mock
    private AudioDetectionClient audioDetectionClient;

    @Mock
    private com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository analysisRecordRepository;

    private AudioAnalysisService audioAnalysisService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        audioAnalysisService = new AudioAnalysisService(audioDetectionClient, analysisRecordRepository);
    }

    @Test
    void analyzeAudio_withValidWav_shouldReturnAnalysisResult() throws Exception {
        var file = new MockMultipartFile("file", "test.wav", "audio/wav", "fake-bytes".getBytes());
        var memberId = java.util.UUID.randomUUID();
        var expected = new AudioAnalysisResult(new AudioDetectionResult("antideepfake", 0.87, List.of()), null);
        when(audioDetectionClient.detectAudio(file.getBytes(), "test.wav", "audio/wav")).thenReturn(expected);

        AudioAnalysisResult result = audioAnalysisService.analyzeAudio(file, memberId);

        assertThat(result.aiDetection().model()).isEqualTo("antideepfake");
        assertThat(result.aiDetection().score()).isEqualTo(0.87);
    }

    @Test
    void analyzeAudio_withEmptyFile_shouldThrowInvalidAudioFileException() {
        var file = new MockMultipartFile("file", "test.wav", "audio/wav", new byte[0]);
        var memberId = java.util.UUID.randomUUID();

        assertThatThrownBy(() -> audioAnalysisService.analyzeAudio(file, memberId))
                .isInstanceOf(InvalidAudioFileException.class);
        verifyNoInteractions(audioDetectionClient);
        verifyNoInteractions(analysisRecordRepository);
    }

    @Test
    void analyzeAudio_withUnsupportedContentType_shouldThrowInvalidAudioFileException() {
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "not-audio".getBytes());
        var memberId = java.util.UUID.randomUUID();

        assertThatThrownBy(() -> audioAnalysisService.analyzeAudio(file, memberId))
                .isInstanceOf(InvalidAudioFileException.class);
        verifyNoInteractions(audioDetectionClient);
        verifyNoInteractions(analysisRecordRepository);
    }

    @Test
    void analyzeAudio_withNullContentType_shouldThrowInvalidAudioFileException() {
        var file = new MockMultipartFile("file", "test.wav", null, "not-audio".getBytes());
        var memberId = java.util.UUID.randomUUID();

        assertThatThrownBy(() -> audioAnalysisService.analyzeAudio(file, memberId))
                .isInstanceOf(InvalidAudioFileException.class);
        verifyNoInteractions(audioDetectionClient);
        verifyNoInteractions(analysisRecordRepository);
    }

    @Test
    void analyzeAudio_withFileLargerThan25Mb_shouldThrowInvalidAudioFileException() {
        byte[] tooLarge = new byte[26 * 1024 * 1024];
        var file = new MockMultipartFile("file", "test.wav", "audio/wav", tooLarge);
        var memberId = java.util.UUID.randomUUID();

        assertThatThrownBy(() -> audioAnalysisService.analyzeAudio(file, memberId))
                .isInstanceOf(InvalidAudioFileException.class);
        verifyNoInteractions(audioDetectionClient);
        verifyNoInteractions(analysisRecordRepository);
    }

    @Test
    void analyzeAudio_withValidWav_shouldSaveCompletedAnalysisRecord() throws Exception {
        var file = new MockMultipartFile("file", "test.wav", "audio/wav", "fake-bytes".getBytes());
        var memberId = java.util.UUID.randomUUID();
        var expected = new AudioAnalysisResult(new AudioDetectionResult("antideepfake", 0.87, List.of()), null);
        when(audioDetectionClient.detectAudio(file.getBytes(), "test.wav", "audio/wav")).thenReturn(expected);

        audioAnalysisService.analyzeAudio(file, memberId);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord.class);
        org.mockito.Mockito.verify(analysisRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getMemberId()).isEqualTo(memberId);
        assertThat(captor.getValue().getModality()).isEqualTo(com.veritae.veritae_server.domain.analysisrecord.Modality.AUDIO);
        assertThat(captor.getValue().getAiScore()).isEqualTo(0.87);
    }
}
