package com.veritae.veritae_server.domain.analysisrecord;

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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 회원의 분석(이미지/음성/영상) 기록. 영상은 처리시간을 예측하기 어려워 PENDING/PROCESSING을
 * 거치는 비동기 흐름을 유지하고(docs/superpowers/specs/2026-08-26-video-ai-detection-design.md §5),
 * 이미지/음성은 동기라 생성과 동시에 COMPLETED로 저장된다
 * (docs/superpowers/specs/2026-09-23-analysis-history-report-design.md §3).
 */
@Entity
@Table(name = "analysis_jobs", indexes = @Index(name = "idx_analysis_jobs_member_id", columnList = "member_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
public class AnalysisRecord {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private Modality modality;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisJobStatus status;

    // columnDefinition을 명시하지 않으면 Hibernate가 @Lob String을 MySQL에서 TEXT(64KB
    // 한계)로 매핑해, evidenceImage(base64 PNG 히트맵)가 포함된 영상 결과 JSON이 그 이상으로
    // 커지면 "Data too long for column" 오류가 난다(2026-08-27 최초 발견).
    @Lob
    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    // 리포트 집계용으로 result_json에서 뽑아 별도 저장 — 파싱 없이 바로 집계 쿼리가 가능하게
    // 하려는 목적(docs/superpowers/specs/2026-09-23-analysis-history-report-design.md §3).
    // aiDetection/scamDetection 자체가 없으면(얼굴없음, 텍스트 미추출 등) null.
    @Column(name = "ai_score")
    private Double aiScore;

    @Column(name = "scam_score")
    private Double scamScore;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    // 원본 파일이 저장소(R2)에 있으면 그 위치, 없으면 null(보관 기능 이전 기록, 저장 실패, 최신 10건
    // 밖이라 지워진 원본 등). 파일 자체는 DB 에 두지 않는다.
    @Column(name = "media_key", length = 200)
    private String mediaKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private AnalysisRecord(UUID id, UUID memberId, Modality modality, AnalysisJobStatus status, Instant createdAt) {
        this.id = id;
        this.memberId = memberId;
        this.modality = modality;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /** 영상 전용 — 비동기 접수(PENDING). */
    public static AnalysisRecord submit(UUID memberId) {
        Instant now = now();
        return new AnalysisRecord(UUID.randomUUID(), memberId, Modality.VIDEO, AnalysisJobStatus.PENDING, now);
    }

    /** 이미지/음성 전용 — 동기 분석이 이미 끝난 결과를 COMPLETED 상태로 바로 생성(상태 전환 없음). */
    public static AnalysisRecord completedSync(
            UUID memberId, Modality modality, String resultJson, Double aiScore, Double scamScore) {
        Instant now = now();
        AnalysisRecord record = new AnalysisRecord(UUID.randomUUID(), memberId, modality, AnalysisJobStatus.COMPLETED, now);
        record.resultJson = resultJson;
        record.aiScore = aiScore;
        record.scamScore = scamScore;
        return record;
    }

    public void markProcessing() {
        this.status = AnalysisJobStatus.PROCESSING;
        this.updatedAt = now();
    }

    public void markCompleted(String resultJson, Double aiScore, Double scamScore) {
        this.status = AnalysisJobStatus.COMPLETED;
        this.resultJson = resultJson;
        this.aiScore = aiScore;
        this.scamScore = scamScore;
        this.updatedAt = now();
    }

    public void markCompletedWithPartialError(
            String resultJson, Double aiScore, Double scamScore, String errorCode, String errorMessage) {
        this.status = AnalysisJobStatus.COMPLETED;
        this.resultJson = resultJson;
        this.aiScore = aiScore;
        this.scamScore = scamScore;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.updatedAt = now();
    }

    public void attachMedia(String mediaKey) {
        this.mediaKey = mediaKey;
        this.updatedAt = now();
    }

    public boolean hasMedia() {
        return mediaKey != null;
    }

    public void markFailed(String errorCode, String errorMessage) {
        this.status = AnalysisJobStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.updatedAt = now();
    }

    // DB(MySQL datetime(6))는 마이크로초까지만 저장한다. 메모리 값이 나노초를 가지고 있으면 같은 기록인데도
    // 저장 전후 값이 달라져, 생성 시각으로 "나보다 먼저 들어온 작업"을 셀 때 자기 자신이 섞일 수 있다.
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
