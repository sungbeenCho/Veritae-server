package com.veritae.veritae_server.detection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScamDetectionTypesTest {

    @Test
    void scamDetectionResult_exposesFieldsViaRecordAccessors() {
        var evidence = new ScamEvidence("계좌번호를 알려주세요", 0.95);
        var result = new ScamDetectionResult("lilju", 0.82, List.of(evidence));

        assertThat(result.model()).isEqualTo("lilju");
        assertThat(result.score()).isEqualTo(0.82);
        assertThat(result.evidence()).containsExactly(evidence);
    }

    @Test
    void imageAnalysisResult_allowsNullScamDetection() {
        var aiDetection = new ImageDetectionResult("spai", 0.1, null);

        var result = new ImageAnalysisResult(aiDetection, null);

        assertThat(result.aiDetection()).isEqualTo(aiDetection);
        assertThat(result.scamDetection()).isNull();
    }

    @Test
    void audioAnalysisResult_andVideoAnalysisResult_exposeAiAndScamDetection() {
        var scamDetection = new ScamDetectionResult("lilju", 0.3, List.of());

        var audioResult = new AudioAnalysisResult(new AudioDetectionResult("antideepfake", 0.1, List.of()), scamDetection);
        var videoResult = new VideoAnalysisResult(new VideoDetectionResult("dfdc", 0.2, List.of(), null), scamDetection);

        assertThat(audioResult.scamDetection()).isEqualTo(scamDetection);
        assertThat(videoResult.scamDetection()).isEqualTo(scamDetection);
    }
}
