package com.veritae.veritae_server.detection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MisinformationDetectionTypesTest {

    @Test
    void misinformationDetectionResult_exposesFieldsViaRecordAccessors() {
        var evidence = new WikiEvidence("선풍기 사망설", "선풍기 사망설은 미신이다.", "https://ko.wikipedia.org/wiki/선풍기_사망설");
        var claim = new MisinformationClaim("선풍기를 틀고 자면 사망한다.", "근거 문단이 주장을 부정합니다.", List.of(evidence));
        var result = new MisinformationDetectionResult("qwen3.5:4b", "2026-09-01", List.of(claim));

        assertThat(result.model()).isEqualTo("qwen3.5:4b");
        assertThat(result.wikiSnapshot()).isEqualTo("2026-09-01");
        assertThat(result.claims()).containsExactly(claim);
        assertThat(result.claims().get(0).evidence()).containsExactly(evidence);
    }

    @Test
    void misinformationDetectionJson_toMisinformationDetection_returnsNullForNullDto() {
        assertThat(MisinformationDetectionJson.toMisinformationDetection(null)).isNull();
    }

    @Test
    void misinformationDetectionJson_toMisinformationDetection_mapsNestedEvidence() {
        var evidenceDto = new MisinformationDetectionJson.WikiEvidenceDto(
                "선풍기 사망설", "선풍기 사망설은 미신이다.", "https://ko.wikipedia.org/wiki/선풍기_사망설");
        var claimDto = new MisinformationDetectionJson.ClaimDto(
                "선풍기를 틀고 자면 사망한다.", "근거 문단이 주장을 부정합니다.", List.of(evidenceDto));
        var dto = new MisinformationDetectionJson.Dto("qwen3.5:4b", "2026-09-01", List.of(claimDto));

        var result = MisinformationDetectionJson.toMisinformationDetection(dto);

        assertThat(result.model()).isEqualTo("qwen3.5:4b");
        assertThat(result.wikiSnapshot()).isEqualTo("2026-09-01");
        assertThat(result.claims()).hasSize(1);
        assertThat(result.claims().get(0).sentence()).isEqualTo("선풍기를 틀고 자면 사망한다.");
        assertThat(result.claims().get(0).evidence().get(0).title()).isEqualTo("선풍기 사망설");
    }
}
