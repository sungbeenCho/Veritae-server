package com.veritae.veritae_server.detection;

/** 가짜정보탐지 판정의 근거로 쓴 위키 조각 한 개. 위키백과 CC BY-SA 출처 표시 요건 때문에
 * title/url이 필수다. */
public record WikiEvidence(String title, String text, String url) {
}
