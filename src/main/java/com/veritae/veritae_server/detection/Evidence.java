package com.veritae.veritae_server.detection;

import java.util.List;

/**
 * AI 판독 근거 카드 한 장. 시간 구간(temporal) 근거만 지원한다 - 주파수 대역(spectral)
 * 근거는 이번 스코프에 없다 (docs/superpowers/specs/2026-08-18-audio-ai-detection-design.md §3).
 */
public record Evidence(String title, String description, List<String> tags, Double startSec, Double endSec) {
}
