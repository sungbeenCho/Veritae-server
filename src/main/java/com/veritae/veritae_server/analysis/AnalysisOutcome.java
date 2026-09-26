package com.veritae.veritae_server.analysis;

import java.util.UUID;

/**
 * 동기 분석(이미지/음성)의 결과와, 그 결과가 저장된 분석 기록 id. 기록 id는 응답의 id로 나가
 * 원본 다운로드(GET /records/{id}/media)에 쓰인다.
 */
public record AnalysisOutcome<T>(UUID recordId, T result) {
}
