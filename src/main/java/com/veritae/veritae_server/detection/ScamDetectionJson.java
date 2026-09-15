package com.veritae.veritae_server.detection;

import java.util.List;

/**
 * 탐지 서버 응답의 {@code scam_detection} JSON 필드를 {@link ScamDetectionResult}로 변환하는
 * 공용 파싱 로직. spai/antideepfake/dfdc 세 HTTP 클라이언트가 각자 응답 스키마 안에서
 * {@code @JsonProperty("scam_detection") ScamDetectionJson.Dto scamDetection} 필드로
 * 이 DTO를 참조하고, {@link #toScamDetection(Dto)}로 도메인 타입으로 변환한다.
 * 세 클라이언트가 바이트 단위로 동일한 DTO/변환 로직을 각자 중복 정의하던 것을
 * 여기 한 곳으로 모았다(2026-09-15 최종 리뷰 반영).
 */
public final class ScamDetectionJson {

    private ScamDetectionJson() {
    }

    public record Dto(String model, double score, List<EvidenceDto> evidence) {
    }

    public record EvidenceDto(String sentence, double score) {
    }

    public static ScamDetectionResult toScamDetection(Dto dto) {
        if (dto == null) {
            return null;
        }
        List<ScamEvidence> evidence = dto.evidence().stream()
                .map(e -> new ScamEvidence(e.sentence(), e.score()))
                .toList();
        return new ScamDetectionResult(dto.model(), dto.score(), evidence);
    }
}
