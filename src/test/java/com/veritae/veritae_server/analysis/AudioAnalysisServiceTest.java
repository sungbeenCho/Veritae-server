package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.detection.AudioDetectionClient;
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

    private AudioAnalysisService audioAnalysisService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        audioAnalysisService = new AudioAnalysisService(audioDetectionClient);
    }

    @Test
    void analyzeAudio_withValidWav_shouldReturnDetectionResult() throws Exception {
        // Given
        var file = new MockMultipartFile("file", "test.wav", "audio/wav", "fake-bytes".getBytes());
        when(audioDetectionClient.detectAudio(file.getBytes(), "test.wav", "audio/wav"))
                .thenReturn(new AiDetectionResult("antideepfake", 0.87, List.of()));

        // When
        AiDetectionResult result = audioAnalysisService.analyzeAudio(file);

        // Then
        assertThat(result.model()).isEqualTo("antideepfake");
        assertThat(result.score()).isEqualTo(0.87);
    }

    @Test
    void analyzeAudio_withEmptyFile_shouldThrowInvalidAudioFileException() {
        // Given
        var file = new MockMultipartFile("file", "test.wav", "audio/wav", new byte[0]);

        // When / Then
        assertThatThrownBy(() -> audioAnalysisService.analyzeAudio(file))
                .isInstanceOf(InvalidAudioFileException.class);
        verifyNoInteractions(audioDetectionClient);
    }

    @Test
    void analyzeAudio_withUnsupportedContentType_shouldThrowInvalidAudioFileException() {
        // Given
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "not-audio".getBytes());

        // When / Then
        assertThatThrownBy(() -> audioAnalysisService.analyzeAudio(file))
                .isInstanceOf(InvalidAudioFileException.class);
        verifyNoInteractions(audioDetectionClient);
    }

    @Test
    void analyzeAudio_withNullContentType_shouldThrowInvalidAudioFileException() {
        // Given
        var file = new MockMultipartFile("file", "test.wav", null, "not-audio".getBytes());

        // When / Then
        assertThatThrownBy(() -> audioAnalysisService.analyzeAudio(file))
                .isInstanceOf(InvalidAudioFileException.class);
        verifyNoInteractions(audioDetectionClient);
    }

    @Test
    void analyzeAudio_withFileLargerThan25Mb_shouldThrowInvalidAudioFileException() {
        // Given
        byte[] tooLarge = new byte[26 * 1024 * 1024];
        var file = new MockMultipartFile("file", "test.wav", "audio/wav", tooLarge);

        // When / Then
        assertThatThrownBy(() -> audioAnalysisService.analyzeAudio(file))
                .isInstanceOf(InvalidAudioFileException.class);
        verifyNoInteractions(audioDetectionClient);
    }
}
