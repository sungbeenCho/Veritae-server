package com.veritae.veritae_server.detection;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 탐지 서버 응답의 {@code misinformation_detection} JSON 필드를
 * {@link MisinformationDetectionResult}로 변환하는 공용 파싱 로직. ScamDetectionJson과
 * 동일한 패턴 - 세 HTTP 클라이언트가 각자 응답 스키마 안에서
 * {@code @JsonProperty("misinformation_detection") MisinformationDetectionJson.Dto} 필드로
 * 이 DTO를 참조한다.
 */
public final class MisinformationDetectionJson {

    private MisinformationDetectionJson() {
    }

    // claims/evidence는 ScamDetectionJson.Dto.evidence와 동일한 전제 - 탐지 서버(misinfo_infer.py)가
    // 근거가 없을 때도 항상 빈 배열([])을 보내지 null을 보내지 않는다. 그래서 스트림 전에 별도 null
    // 가드를 두지 않는다(2026-09-30 최종 리뷰에서 의도적으로 확인).
    public record Dto(String model, @JsonProperty("wiki_snapshot") String wikiSnapshot, List<ClaimDto> claims) {
    }

    public record ClaimDto(String sentence, String reason, List<WikiEvidenceDto> evidence) {
    }

    public record WikiEvidenceDto(String title, String text, String url) {
    }

    public static MisinformationDetectionResult toMisinformationDetection(Dto dto) {
        if (dto == null) {
            return null;
        }
        List<MisinformationClaim> claims = dto.claims().stream()
                .map(c -> new MisinformationClaim(
                        c.sentence(), c.reason(),
                        c.evidence().stream()
                                .map(e -> new WikiEvidence(e.title(), e.text(), e.url()))
                                .toList()))
                .toList();
        return new MisinformationDetectionResult(dto.model(), dto.wikiSnapshot(), claims);
    }
}
