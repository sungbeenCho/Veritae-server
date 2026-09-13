package com.veritae.veritae_server.detection;

/**
 * 사기감지 근거 카드 한 장 - 위험하다고 판단된 문장 원문과 그 문장의 개별 확률만 담는다.
 * 유형 라벨("기관사칭" 등)은 포함하지 않는다 - 라벨을 만들려면 키워드/규칙 기반 분류가
 * 필요한데 이미 사용자가 거절한 방향이라 채택하지 않았다(2026-09-13 설계).
 */
public record ScamEvidence(String sentence, double score) {
}
