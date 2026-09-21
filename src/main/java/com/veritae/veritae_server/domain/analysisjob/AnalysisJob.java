package com.veritae.veritae_server.domain.analysisjob;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 영상 분석 비동기 작업. 이미지/음성은 동기라 이런 엔티티가 없다 - 영상만 처리시간을
 * 예측하기 어려워 처음부터 잡 모델로 설계했다
 * (docs/superpowers/specs/2026-08-26-video-ai-detection-design.md §5).
 */
@Entity
@Table(name = "analysis_jobs", indexes = @Index(name = "idx_analysis_jobs_member_id", columnList = "member_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
public class AnalysisJob {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisJobStatus status;

    // columnDefinition을 명시하지 않으면 Hibernate가 @Lob String을 MySQL에서 TEXT(64KB
    // 한계)로 매핑해, evidenceImage(base64 PNG 히트맵)가 포함된 영상 결과 JSON이 그 이상으로
    // 커지면 "Data too long for column" 오류가 난다(2026-08-27, 실제 Spring↔데스크탑 왕복
    // 테스트 중 처음 발견 - 이미지/음성은 evidenceImage를 안 써서 이 문제가 없었음).
    @Lob
    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private AnalysisJob(UUID id, UUID memberId, AnalysisJobStatus status, Instant createdAt) {
        this.id = id;
        this.memberId = memberId;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public static AnalysisJob submit(UUID memberId) {
        Instant now = Instant.now();
        return new AnalysisJob(UUID.randomUUID(), memberId, AnalysisJobStatus.PENDING, now);
    }

    public void markProcessing() {
        this.status = AnalysisJobStatus.PROCESSING;
        this.updatedAt = Instant.now();
    }

    public void markCompleted(String resultJson) {
        this.status = AnalysisJobStatus.COMPLETED;
        this.resultJson = resultJson;
        this.updatedAt = Instant.now();
    }

    // 판독 종류 중 일부(예: 얼굴없음으로 AI판독 불가)만 정상적인 이유로 비어있고 나머지는
    // 살아있는 경우를 위한 메서드다. status는 COMPLETED로 유지하되 errorCode/errorMessage에
    // "왜 일부가 비어있는지"를 담는다 - markFailed와 달리 완전 실패가 아니다(2026-09-21).
    public void markCompletedWithPartialError(String resultJson, String errorCode, String errorMessage) {
        this.status = AnalysisJobStatus.COMPLETED;
        this.resultJson = resultJson;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String errorCode, String errorMessage) {
        this.status = AnalysisJobStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.updatedAt = Instant.now();
    }
}
