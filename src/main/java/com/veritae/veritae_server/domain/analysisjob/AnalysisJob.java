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

    @Lob
    @Column(name = "result_json")
    private String resultJson;

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

    public void markFailed(String errorMessage) {
        this.status = AnalysisJobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.updatedAt = Instant.now();
    }
}
