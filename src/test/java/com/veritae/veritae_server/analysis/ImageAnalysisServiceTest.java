package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.DetectionClient;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
import com.veritae.veritae_server.detection.ImageDetectionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageAnalysisServiceTest {

    @Mock
    private DetectionClient detectionClient;

    private ImageAnalysisService imageAnalysisService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        imageAnalysisService = new ImageAnalysisService(detectionClient);
    }

    @Test
    void analyzeImage_withValidJpeg_shouldReturnAnalysisResult() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());
        var expected = new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, null), null);
        when(detectionClient.detectImage(file.getBytes(), "test.jpg", "image/jpeg")).thenReturn(expected);

        ImageAnalysisResult result = imageAnalysisService.analyzeImage(file);

        assertThat(result.aiDetection().model()).isEqualTo("spai");
        assertThat(result.aiDetection().score()).isEqualTo(0.87);
    }

    @Test
    void analyzeImage_withEmptyFile_shouldThrowInvalidImageFileException() {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> imageAnalysisService.analyzeImage(file))
                .isInstanceOf(InvalidImageFileException.class);
        verifyNoInteractions(detectionClient);
    }

    @Test
    void analyzeImage_withUnsupportedContentType_shouldThrowInvalidImageFileException() {
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "not-an-image".getBytes());

        assertThatThrownBy(() -> imageAnalysisService.analyzeImage(file))
                .isInstanceOf(InvalidImageFileException.class);
        verifyNoInteractions(detectionClient);
    }
}
