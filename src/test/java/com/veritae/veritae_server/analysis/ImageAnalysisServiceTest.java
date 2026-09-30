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

    @Mock
    private com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository analysisRecordRepository;

    private com.veritae.veritae_server.media.InMemoryMediaStorage mediaStorage;

    private ImageAnalysisService imageAnalysisService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        mediaStorage = new com.veritae.veritae_server.media.InMemoryMediaStorage();
        imageAnalysisService = new ImageAnalysisService(detectionClient,
                new AnalysisMediaService(mediaStorage, analysisRecordRepository));
    }

    @Test
    void analyzeImage_withValidJpeg_shouldReturnAnalysisResult() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());
        var memberId = java.util.UUID.randomUUID();
        var expected = new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, null), null, null);
        when(detectionClient.detectImage(file.getBytes(), "test.jpg", "image/jpeg")).thenReturn(expected);

        ImageAnalysisResult result = imageAnalysisService.analyzeImage(file, memberId).result();

        assertThat(result.aiDetection().model()).isEqualTo("spai");
        assertThat(result.aiDetection().score()).isEqualTo(0.87);
    }

    @Test
    void analyzeImage_withEmptyFile_shouldThrowInvalidImageFileException() {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", new byte[0]);
        var memberId = java.util.UUID.randomUUID();

        assertThatThrownBy(() -> imageAnalysisService.analyzeImage(file, memberId))
                .isInstanceOf(InvalidImageFileException.class);
        verifyNoInteractions(detectionClient);
        verifyNoInteractions(analysisRecordRepository);
    }

    @Test
    void analyzeImage_withUnsupportedContentType_shouldThrowInvalidImageFileException() {
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "not-an-image".getBytes());
        var memberId = java.util.UUID.randomUUID();

        assertThatThrownBy(() -> imageAnalysisService.analyzeImage(file, memberId))
                .isInstanceOf(InvalidImageFileException.class);
        verifyNoInteractions(detectionClient);
        verifyNoInteractions(analysisRecordRepository);
    }

    @Test
    void analyzeImage_withValidJpeg_shouldSaveCompletedAnalysisRecord() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());
        var memberId = java.util.UUID.randomUUID();
        var expected = new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, null), null, null);
        when(detectionClient.detectImage(file.getBytes(), "test.jpg", "image/jpeg")).thenReturn(expected);

        imageAnalysisService.analyzeImage(file, memberId);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord.class);
        org.mockito.Mockito.verify(analysisRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getMemberId()).isEqualTo(memberId);
        assertThat(captor.getValue().getModality()).isEqualTo(com.veritae.veritae_server.domain.analysisrecord.Modality.IMAGE);
        assertThat(captor.getValue().getAiScore()).isEqualTo(0.87);
    }

    @Test
    void analyzeImage_withMisinformationClaims_shouldSaveMisinfoRefutedCount() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());
        var memberId = java.util.UUID.randomUUID();
        var wikiEvidence = new com.veritae.veritae_server.detection.WikiEvidence(
                "선풍기 사망설", "선풍기 사망설이란 미신이다.", "https://ko.wikipedia.org/wiki/선풍기_사망설");
        var claims = java.util.List.of(
                new com.veritae.veritae_server.detection.MisinformationClaim(
                        "선풍기를 틀고 자면 사망한다.", "근거 없음", java.util.List.of(wikiEvidence)),
                new com.veritae.veritae_server.detection.MisinformationClaim(
                        "다른 거짓 주장", "근거 없음", java.util.List.of(wikiEvidence)));
        var misinformation = new com.veritae.veritae_server.detection.MisinformationDetectionResult(
                "qwen3.5:4b", "2026-09-01", claims);
        var expected = new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, null), null, misinformation);
        when(detectionClient.detectImage(file.getBytes(), "test.jpg", "image/jpeg")).thenReturn(expected);

        imageAnalysisService.analyzeImage(file, memberId);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord.class);
        org.mockito.Mockito.verify(analysisRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getMisinfoRefutedCount()).isEqualTo(2);
    }

    @Test
    void analyzeImage_withValidJpeg_shouldReturnIdOfSavedRecord() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());
        var memberId = java.util.UUID.randomUUID();
        when(detectionClient.detectImage(file.getBytes(), "test.jpg", "image/jpeg"))
                .thenReturn(new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, null), null, null));

        var outcome = imageAnalysisService.analyzeImage(file, memberId);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord.class);
        org.mockito.Mockito.verify(analysisRecordRepository).save(captor.capture());
        assertThat(outcome.recordId()).isEqualTo(captor.getValue().getId());
    }

    @Test
    void analyzeImage_withValidJpeg_shouldStoreOriginalAndAttachItToRecord() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());
        var memberId = java.util.UUID.randomUUID();
        when(detectionClient.detectImage(file.getBytes(), "test.jpg", "image/jpeg"))
                .thenReturn(new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, null), null, null));

        imageAnalysisService.analyzeImage(file, memberId);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord.class);
        org.mockito.Mockito.verify(analysisRecordRepository).save(captor.capture());
        String mediaKey = captor.getValue().getMediaKey();
        assertThat(mediaKey).isEqualTo("media/" + memberId + "/" + captor.getValue().getId());
        assertThat(mediaStorage.objects().get(mediaKey)).isEqualTo("fake-bytes".getBytes());
        org.mockito.Mockito.verify(analysisRecordRepository)
                .findByMemberIdAndMediaKeyIsNotNullOrderByCreatedAtDesc(memberId);
    }

    @Test
    void analyzeImage_whenMediaStorageDisabled_shouldStillSaveRecordWithoutMedia() throws Exception {
        mediaStorage.disable();
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());
        var memberId = java.util.UUID.randomUUID();
        when(detectionClient.detectImage(file.getBytes(), "test.jpg", "image/jpeg"))
                .thenReturn(new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, null), null, null));

        imageAnalysisService.analyzeImage(file, memberId);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord.class);
        org.mockito.Mockito.verify(analysisRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getMediaKey()).isNull();
    }
}
