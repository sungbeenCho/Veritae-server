package com.veritae.veritae_server.detection;

import java.util.List;

/** 가짜정보(허위정보) 판별 결과. 텍스트가 전혀 추출되지 않으면 이 필드 자체가 없다(null),
 * scamDetection과 같은 조건. claims가 빈 배열이면 "검사했지만 반박되는 내용을 못 찾음"이지
 * "전부 사실"이 아니다. */
public record MisinformationDetectionResult(String model, String wikiSnapshot, List<MisinformationClaim> claims) {
}
