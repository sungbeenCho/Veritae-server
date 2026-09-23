# 회원별 분석기록/리포트 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 이미지/음성/영상 분석 결과를 회원별로 저장하고, 완료된 기록 목록(페이지네이션) 조회 API와 집계 리포트 조회 API를 별도로 제공한다.

**Architecture:** 기존 `AnalysisJob`(영상 전용) 엔티티를 `AnalysisRecord`로 리네이밍·확장해 `modality`(IMAGE/AUDIO/VIDEO) 컬럼과 리포트 집계용 `ai_score`/`scam_score` 컬럼을 추가한다. 새 테이블은 만들지 않는다. 이미지/음성 분석 성공 직후 COMPLETED 상태로 한 번에 저장하고, 영상은 기존 PENDING→PROCESSING→COMPLETED/FAILED 흐름을 그대로 유지한다.

**Tech Stack:** Spring Boot 4, Spring Data JPA(MySQL), openapi-generator(API-first), JUnit 5 + Mockito + AssertJ

**Spec:** `docs/superpowers/specs/2026-09-23-analysis-history-report-design.md`

## Global Constraints

- 새 테이블을 만들지 않는다 — 기존 `analysis_jobs` 테이블을 확장한다 (스펙 §3).
- 이력 목록에는 `status=COMPLETED`인 기록만 노출한다 (스펙 §2).
- 보관 기간/개수 제한 없음 — 전부 저장, 조회 시 페이지네이션만 적용 (스펙 §2).
- 리포트는 별도 API로 분리한다, PDF 등 파일 생성은 하지 않는다 (스펙 §2).
- 다른 회원의 데이터는 절대 노출하지 않는다 — 모든 조회는 인증된 토큰의 memberId로만 필터링한다, URL에 회원 식별자를 받지 않는다 (스펙 §4, §6).
- `AnalysisJobStatus`는 이름을 유지한다 (스펙 §3).
- `AnalysisJobView`/`AnalysisJobNotFoundException`(스펙 §3에서 "구현 단계에서 확정"으로 남겨둔 항목)은 **리네이밍하지 않는다** — 영상 job 폴링(`GET /api/v1/analysis/jobs/{jobId}`) 전용 타입으로 계속 쓰이므로 이름이 여전히 정확하다. 새 이력 목록 응답은 `AnalysisRecordSummary`(openapi 생성 타입)를 별도로 쓴다.

---

## Task 1: `AnalysisRecord` 엔티티 — 리네이밍 + `modality`/`ai_score`/`scam_score` 추가

**Files:**
- Create: `src/main/java/com/veritae/veritae_server/domain/analysisrecord/Modality.java`
- Create: `src/main/java/com/veritae/veritae_server/domain/analysisrecord/AnalysisRecord.java`
- Create: `src/main/java/com/veritae/veritae_server/domain/analysisrecord/AnalysisJobStatus.java` (기존 `domain/analysisjob/AnalysisJobStatus.java`에서 패키지만 이동, 내용 동일)
- Delete: `src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJob.java`
- Delete: `src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobStatus.java`
- Test: `src/test/java/com/veritae/veritae_server/domain/analysisrecord/AnalysisRecordTest.java` (기존 `domain/analysisjob/AnalysisJobTest.java` 대체)
- Delete: `src/test/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobTest.java`

**Interfaces:**
- Produces: `AnalysisRecord.submit(UUID memberId)` → `AnalysisRecord`(영상용, PENDING), `AnalysisRecord.completedSync(UUID memberId, Modality modality, String resultJson, Double aiScore, Double scamScore)` → `AnalysisRecord`(이미지/음성용, COMPLETED), `markProcessing()`, `markCompleted(String resultJson, Double aiScore, Double scamScore)`, `markCompletedWithPartialError(String resultJson, Double aiScore, Double scamScore, String errorCode, String errorMessage)`, `markFailed(String errorCode, String errorMessage)`, getter들(`getId`, `getMemberId`, `getModality`, `getStatus`, `getResultJson`, `getAiScore`, `getScamScore`, `getErrorCode`, `getErrorMessage`, `getCreatedAt`, `getUpdatedAt`)
- Consumes: 없음 (도메인 최하위 계층)

- [ ] **Step 1: `Modality` enum 작성**

```java
package com.veritae.veritae_server.domain.analysisrecord;

public enum Modality {
    IMAGE,
    AUDIO,
    VIDEO
}
```

- [ ] **Step 2: 실패하는 테스트 작성** (`AnalysisRecordTest.java`)

```java
package com.veritae.veritae_server.domain.analysisrecord;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisRecordTest {

    @Test
    void submit_shouldCreatePendingVideoRecord() {
        UUID memberId = UUID.randomUUID();

        AnalysisRecord record = AnalysisRecord.submit(memberId);

        assertThat(record.getId()).isNotNull();
        assertThat(record.getMemberId()).isEqualTo(memberId);
        assertThat(record.getModality()).isEqualTo(Modality.VIDEO);
        assertThat(record.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        assertThat(record.getResultJson()).isNull();
        assertThat(record.getAiScore()).isNull();
        assertThat(record.getScamScore()).isNull();
    }

    @Test
    void completedSync_shouldCreateCompletedImageRecordWithScores() {
        UUID memberId = UUID.randomUUID();

        AnalysisRecord record = AnalysisRecord.completedSync(
                memberId, Modality.IMAGE, "{\"score\":0.87}", 0.87, null);

        assertThat(record.getModality()).isEqualTo(Modality.IMAGE);
        assertThat(record.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(record.getResultJson()).isEqualTo("{\"score\":0.87}");
        assertThat(record.getAiScore()).isEqualTo(0.87);
        assertThat(record.getScamScore()).isNull();
    }

    @Test
    void markProcessing_shouldTransitionToProcessing() {
        AnalysisRecord record = AnalysisRecord.submit(UUID.randomUUID());

        record.markProcessing();

        assertThat(record.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
    }

    @Test
    void markCompleted_shouldTransitionToCompletedAndStoreResultAndScores() {
        AnalysisRecord record = AnalysisRecord.submit(UUID.randomUUID());

        record.markCompleted("{\"score\":0.91}", 0.91, 0.2);

        assertThat(record.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(record.getResultJson()).isEqualTo("{\"score\":0.91}");
        assertThat(record.getAiScore()).isEqualTo(0.91);
        assertThat(record.getScamScore()).isEqualTo(0.2);
    }

    @Test
    void markCompletedWithPartialError_shouldStayCompletedAndStoreResultScoresAndErrorTogether() {
        AnalysisRecord record = AnalysisRecord.submit(UUID.randomUUID());

        record.markCompletedWithPartialError(
                "{\"scamDetection\":{\"score\":0.82}}", null, 0.82, "NO_FACE_DETECTED", "얼굴을 찾을 수 없습니다.");

        assertThat(record.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(record.getResultJson()).isEqualTo("{\"scamDetection\":{\"score\":0.82}}");
        assertThat(record.getAiScore()).isNull();
        assertThat(record.getScamScore()).isEqualTo(0.82);
        assertThat(record.getErrorCode()).isEqualTo("NO_FACE_DETECTED");
        assertThat(record.getErrorMessage()).isEqualTo("얼굴을 찾을 수 없습니다.");
    }

    @Test
    void markFailed_shouldTransitionToFailedAndStoreErrorCodeAndMessage() {
        AnalysisRecord record = AnalysisRecord.submit(UUID.randomUUID());

        record.markFailed("ANALYSIS_FAILED", "탐지 서버 호출 실패");

        assertThat(record.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(record.getErrorCode()).isEqualTo("ANALYSIS_FAILED");
        assertThat(record.getErrorMessage()).isEqualTo("탐지 서버 호출 실패");
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordTest"`
Expected: FAIL (컴파일 오류 — `AnalysisRecord`/`AnalysisJobStatus`가 새 패키지에 아직 없음)

- [ ] **Step 4: `AnalysisJobStatus`를 새 패키지로 이동**

`domain/analysisjob/AnalysisJobStatus.java` 내용을 그대로 `domain/analysisrecord/AnalysisJobStatus.java`로 복사(패키지 선언만 변경), 기존 파일 삭제.

```java
package com.veritae.veritae_server.domain.analysisrecord;

public enum AnalysisJobStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
```

- [ ] **Step 5: `AnalysisRecord` 엔티티 작성**

```java
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
        Instant now = Instant.now();
        return new AnalysisRecord(UUID.randomUUID(), memberId, Modality.VIDEO, AnalysisJobStatus.PENDING, now);
    }

    /** 이미지/음성 전용 — 동기 분석이 이미 끝난 결과를 COMPLETED 상태로 바로 생성(상태 전환 없음). */
    public static AnalysisRecord completedSync(
            UUID memberId, Modality modality, String resultJson, Double aiScore, Double scamScore) {
        Instant now = Instant.now();
        AnalysisRecord record = new AnalysisRecord(UUID.randomUUID(), memberId, modality, AnalysisJobStatus.COMPLETED, now);
        record.resultJson = resultJson;
        record.aiScore = aiScore;
        record.scamScore = scamScore;
        return record;
    }

    public void markProcessing() {
        this.status = AnalysisJobStatus.PROCESSING;
        this.updatedAt = Instant.now();
    }

    public void markCompleted(String resultJson, Double aiScore, Double scamScore) {
        this.status = AnalysisJobStatus.COMPLETED;
        this.resultJson = resultJson;
        this.aiScore = aiScore;
        this.scamScore = scamScore;
        this.updatedAt = Instant.now();
    }

    public void markCompletedWithPartialError(
            String resultJson, Double aiScore, Double scamScore, String errorCode, String errorMessage) {
        this.status = AnalysisJobStatus.COMPLETED;
        this.resultJson = resultJson;
        this.aiScore = aiScore;
        this.scamScore = scamScore;
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
```

- [ ] **Step 6: 기존 `domain/analysisjob/` 파일 삭제**

```bash
git rm src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJob.java
git rm src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobStatus.java
git rm src/test/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobTest.java
```

- [ ] **Step 7: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordTest"`
Expected: PASS (다른 클래스들이 아직 옛 패키지를 참조해 전체 빌드는 이 시점에 깨져 있는 게 정상 — Task 3에서 고침. 이 테스트 클래스만 격리 실행)

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/domain/analysisrecord src/test/java/com/veritae/veritae_server/domain/analysisrecord
git commit -m "refactor(analysis): AnalysisJob을 AnalysisRecord로 리네이밍하고 modality/ai_score/scam_score 추가"
```

---

## Task 2: `AnalysisRecordRepository` — 리네이밍 + 페이지네이션/집계 쿼리 추가

**Files:**
- Create: `src/main/java/com/veritae/veritae_server/domain/analysisrecord/AnalysisRecordRepository.java`
- Delete: `src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobRepository.java`
- Test: `src/test/java/com/veritae/veritae_server/domain/analysisrecord/AnalysisRecordRepositoryTest.java`

**Interfaces:**
- Consumes: `AnalysisRecord`, `Modality`, `AnalysisJobStatus` (Task 1)
- Produces: `findByStatusIn(List<AnalysisJobStatus>)` → `List<AnalysisRecord>`, `findByMemberIdAndStatusOrderByCreatedAtDesc(UUID, AnalysisJobStatus, Pageable)` → `Page<AnalysisRecord>`, `countByMemberIdAndStatus(UUID, AnalysisJobStatus)` → `long`, `countByMemberIdAndStatusAndModality(UUID, AnalysisJobStatus, Modality)` → `long`, `countByMemberIdAndStatusAndAiScoreGreaterThanEqual(UUID, AnalysisJobStatus, double)` → `long`, `countByMemberIdAndStatusAndScamScoreGreaterThanEqual(UUID, AnalysisJobStatus, double)` → `long`

- [ ] **Step 1: 실패하는 테스트 작성** (`AnalysisRecordRepositoryTest.java`)

```java
package com.veritae.veritae_server.domain.analysisrecord;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class AnalysisRecordRepositoryTest {

    @org.springframework.beans.factory.annotation.Autowired
    private AnalysisRecordRepository repository;

    @Test
    void findByMemberIdAndStatusOrderByCreatedAtDesc_shouldReturnOnlyThatMembersCompletedRecordsNewestFirst() {
        UUID memberId = UUID.randomUUID();
        UUID otherMemberId = UUID.randomUUID();
        repository.save(AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.1, null));
        repository.save(AnalysisRecord.completedSync(memberId, Modality.AUDIO, "{}", 0.2, null));
        repository.save(AnalysisRecord.submit(memberId)); // PENDING, 목록에서 제외돼야 함
        repository.save(AnalysisRecord.completedSync(otherMemberId, Modality.IMAGE, "{}", 0.3, null));

        Page<AnalysisRecord> page = repository.findByMemberIdAndStatusOrderByCreatedAtDesc(
                memberId, AnalysisJobStatus.COMPLETED, PageRequest.of(0, 10, Sort.by("createdAt").descending()));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allMatch(r -> r.getMemberId().equals(memberId));
        assertThat(page.getContent()).allMatch(r -> r.getStatus() == AnalysisJobStatus.COMPLETED);
    }

    @Test
    void countQueries_shouldAggregateByModalityAndScoreThreshold() {
        UUID memberId = UUID.randomUUID();
        repository.save(AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.9, null));
        repository.save(AnalysisRecord.completedSync(memberId, Modality.AUDIO, "{}", 0.1, 0.6));
        repository.save(AnalysisRecord.completedSync(memberId, Modality.VIDEO, "{}", null, 0.2));

        assertThat(repository.countByMemberIdAndStatus(memberId, AnalysisJobStatus.COMPLETED)).isEqualTo(3);
        assertThat(repository.countByMemberIdAndStatusAndModality(memberId, AnalysisJobStatus.COMPLETED, Modality.IMAGE)).isEqualTo(1);
        assertThat(repository.countByMemberIdAndStatusAndAiScoreGreaterThanEqual(memberId, AnalysisJobStatus.COMPLETED, 0.5)).isEqualTo(1);
        assertThat(repository.countByMemberIdAndStatusAndScamScoreGreaterThanEqual(memberId, AnalysisJobStatus.COMPLETED, 0.5)).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepositoryTest"`
Expected: FAIL (컴파일 오류 — `AnalysisRecordRepository`가 아직 없음)

- [ ] **Step 3: `AnalysisRecordRepository` 작성**

```java
package com.veritae.veritae_server.domain.analysisrecord;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnalysisRecordRepository extends JpaRepository<AnalysisRecord, UUID> {

    List<AnalysisRecord> findByStatusIn(List<AnalysisJobStatus> statuses);

    Page<AnalysisRecord> findByMemberIdAndStatusOrderByCreatedAtDesc(UUID memberId, AnalysisJobStatus status, Pageable pageable);

    long countByMemberIdAndStatus(UUID memberId, AnalysisJobStatus status);

    long countByMemberIdAndStatusAndModality(UUID memberId, AnalysisJobStatus status, Modality modality);

    long countByMemberIdAndStatusAndAiScoreGreaterThanEqual(UUID memberId, AnalysisJobStatus status, double aiScore);

    long countByMemberIdAndStatusAndScamScoreGreaterThanEqual(UUID memberId, AnalysisJobStatus status, double scamScore);
}
```

- [ ] **Step 4: 기존 리포지토리 삭제**

```bash
git rm src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobRepository.java
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepositoryTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/domain/analysisrecord/AnalysisRecordRepository.java src/test/java/com/veritae/veritae_server/domain/analysisrecord/AnalysisRecordRepositoryTest.java
git commit -m "refactor(analysis): AnalysisJobRepository를 AnalysisRecordRepository로 리네이밍하고 페이지네이션/집계 쿼리 추가"
```

---

## Task 3: 영상 파이프라인이 새 엔티티/패키지를 쓰도록 수정 (컴파일 복구)

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/analysis/VideoAnalysisService.java`
- Modify: `src/main/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorker.java`
- Modify: `src/main/java/com/veritae/veritae_server/analysis/StaleAnalysisJobCleaner.java`
- Modify: `src/test/java/com/veritae/veritae_server/analysis/VideoAnalysisServiceTest.java`
- Modify: `src/test/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorkerTest.java`
- Modify: `src/test/java/com/veritae/veritae_server/analysis/StaleAnalysisJobCleanerTest.java`
- Modify: `src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java`

**Interfaces:**
- Consumes: `AnalysisRecord`, `AnalysisRecordRepository`, `AnalysisJobStatus`, `Modality` (Task 1, 2)
- Produces: 기존과 동일한 공개 동작(`VideoAnalysisService.submitVideo`, `getJob`) — 내부 구현만 새 엔티티로 교체

Task 1~2에서 옛 클래스를 삭제해 지금 빌드가 깨져 있다. 이 태스크는 새 코드를 추가하는 게 아니라, 참조하는 이름만 바꿔서 빌드를 복구한다. 각 파일에서 `import ...domain.analysisjob.AnalysisJob;` → `import ...domain.analysisrecord.AnalysisRecord;` 식으로 바꾸고, 타입/변수명 `AnalysisJob`→`AnalysisRecord`, `analysisJobRepository`→`analysisRecordRepository`로 일괄 변경한다.

- [ ] **Step 1: `VideoAnalysisService.java` 수정**

`import com.veritae.veritae_server.domain.analysisjob.AnalysisJob;` / `AnalysisJobRepository` / `AnalysisJobStatus` 세 줄을 다음으로 교체:

```java
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus;
```

필드/파라미터/지역변수의 `AnalysisJobRepository` → `AnalysisRecordRepository`, `AnalysisJob` → `AnalysisRecord`로 전부 교체 (`analysisJobRepository` 필드명은 `analysisRecordRepository`로). `AnalysisJob.submit(memberId)` → `AnalysisRecord.submit(memberId)`로 교체. 나머지 로직(검증, 소유권 체크, `getJob`의 `readResultJson`)은 그대로 둔다.

- [ ] **Step 2: `VideoAnalysisAsyncWorker.java` 수정**

import 3줄 교체는 Step 1과 동일. `process()` 메서드에서 `markCompleted`/`markCompletedWithPartialError` 호출부를 새 시그니처(점수 인자 추가)에 맞춰 수정한다 — `VideoAnalysisResult`는 이미 타입이 있는 `aiDetection()`/`scamDetection()`을 갖고 있으므로 거기서 점수를 직접 꺼낸다:

```java
    @Async("videoAnalysisExecutor")
    public void process(UUID jobId, byte[] videoBytes, String filename, String contentType) {
        AnalysisRecord record = analysisRecordRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("방금 생성한 job을 찾을 수 없습니다: " + jobId));
        record.markProcessing();
        analysisRecordRepository.save(record);

        try {
            VideoAnalysisResult result = videoDetectionClient.detectVideo(videoBytes, filename, contentType);
            Double aiScore = result.aiDetection() != null ? result.aiDetection().score() : null;
            Double scamScore = result.scamDetection() != null ? result.scamDetection().score() : null;
            if (result.errorCode() != null) {
                log.info("영상 분석: 일부 판독 불가 jobId={} errorCode={}", jobId, result.errorCode());
                record.markCompletedWithPartialError(
                        writeResultJson(result), aiScore, scamScore, result.errorCode(), errorMessageFor(result.errorCode()));
            } else {
                record.markCompleted(writeResultJson(result), aiScore, scamScore);
            }
        } catch (Exception e) {
            log.error("영상 분석 실패 jobId={}", jobId, e);
            record.markFailed("ANALYSIS_FAILED", "영상 분석 중 오류가 발생했습니다.");
        }
        analysisRecordRepository.save(record);
    }
```

필드 선언(`private final AnalysisJobRepository analysisJobRepository;` → `private final AnalysisRecordRepository analysisRecordRepository;`)과 import도 교체.

- [ ] **Step 3: `StaleAnalysisJobCleaner.java` 수정**

import 3줄 교체, `AnalysisJobRepository`→`AnalysisRecordRepository`, `List<AnalysisJob>`→`List<AnalysisRecord>`, 변수명 `job`→`record`로 교체. 로직(`findByStatusIn`, `markFailed`, `saveAll`)은 그대로.

- [ ] **Step 4: 기존 테스트 3개 파일의 import/타입명 교체**

`VideoAnalysisServiceTest.java`, `VideoAnalysisAsyncWorkerTest.java`, `StaleAnalysisJobCleanerTest.java`에서 `AnalysisJob`/`AnalysisJobRepository`를 `AnalysisRecord`/`AnalysisRecordRepository`로, import 경로를 `domain.analysisrecord`로 교체. `markCompleted`/`markCompletedWithPartialError`를 직접 호출/검증하는 테스트가 있다면 새 시그니처(점수 인자)에 맞게 인자를 추가한다.

- [ ] **Step 5: `AnalysisApiControllerTest.java`의 `domain.analysisjob.AnalysisJobStatus` 참조 교체**

163~211번 줄 근방의 `com.veritae.veritae_server.domain.analysisjob.AnalysisJobStatus` 전체경로 참조 2곳을 `com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus`로 교체.

- [ ] **Step 6: 전체 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (테스트 전부 통과, 컴파일 오류 없음)

- [ ] **Step 7: 커밋**

```bash
git add -u
git commit -m "refactor(analysis): 영상 파이프라인이 AnalysisRecord/AnalysisRecordRepository를 쓰도록 수정"
```

---

## Task 4: `ImageAnalysisService`가 분석 성공 시 `AnalysisRecord`를 저장하도록 변경

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/analysis/ImageAnalysisService.java`
- Modify: `src/main/java/com/veritae/veritae_server/api/AnalysisApiDelegateImpl.java`
- Modify: `src/test/java/com/veritae/veritae_server/analysis/ImageAnalysisServiceTest.java`
- Modify: `src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java`

**Interfaces:**
- Consumes: `AnalysisRecord.completedSync`, `AnalysisRecordRepository.save` (Task 1, 2), `AuthenticatedMemberResolver.currentMemberId()` (기존)
- Produces: `ImageAnalysisService.analyzeImage(MultipartFile file, UUID memberId)` → `ImageAnalysisResult` (시그니처 변경 — memberId 추가)

- [ ] **Step 1: 실패하는 테스트 추가** (`ImageAnalysisServiceTest.java`)

기존 `setUp()`에 `AnalysisRecordRepository` mock을 추가하고, 새 테스트를 추가한다:

```java
    @Mock
    private com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository analysisRecordRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        imageAnalysisService = new ImageAnalysisService(detectionClient, analysisRecordRepository);
    }

    @Test
    void analyzeImage_withValidJpeg_shouldSaveCompletedAnalysisRecord() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());
        var memberId = java.util.UUID.randomUUID();
        var expected = new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, null), null);
        when(detectionClient.detectImage(file.getBytes(), "test.jpg", "image/jpeg")).thenReturn(expected);

        imageAnalysisService.analyzeImage(file, memberId);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord.class);
        org.mockito.Mockito.verify(analysisRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getMemberId()).isEqualTo(memberId);
        assertThat(captor.getValue().getModality()).isEqualTo(com.veritae.veritae_server.domain.analysisrecord.Modality.IMAGE);
        assertThat(captor.getValue().getAiScore()).isEqualTo(0.87);
    }
```

기존 `analyzeImage_withEmptyFile_...`/`analyzeImage_withUnsupportedContentType_...` 테스트 호출부에 `memberId` 인자(`java.util.UUID.randomUUID()`)를 추가하고, `verifyNoInteractions(detectionClient)` 옆에 `verifyNoInteractions(analysisRecordRepository)`도 추가한다.

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.ImageAnalysisServiceTest"`
Expected: FAIL (컴파일 오류 — 생성자/메서드 시그니처 불일치)

- [ ] **Step 3: `ImageAnalysisService.java` 수정**

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.DetectionClient;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.analysisrecord.Modality;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageAnalysisService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    // resultJson은 우리가 직접 쓰고 직접 읽는 순수 내부 저장 포맷 — VideoAnalysisAsyncWorker와 동일한
    // 이유로(Jackson 3 스택에서 ObjectMapper 빈 자동등록 안 됨) DI 대신 직접 인스턴스를 만들어 쓴다.
    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final DetectionClient detectionClient;
    private final AnalysisRecordRepository analysisRecordRepository;

    public ImageAnalysisResult analyzeImage(MultipartFile file, UUID memberId) {
        validate(file);
        ImageAnalysisResult result;
        try {
            result = detectionClient.detectImage(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            throw new UncheckedIOException("업로드된 파일을 읽을 수 없습니다.", e);
        }

        Double aiScore = result.aiDetection() != null ? result.aiDetection().score() : null;
        Double scamScore = result.scamDetection() != null ? result.scamDetection().score() : null;
        analysisRecordRepository.save(AnalysisRecord.completedSync(
                memberId, Modality.IMAGE, writeResultJson(result), aiScore, scamScore));

        return result;
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidImageFileException("빈 파일은 분석할 수 없습니다.");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new InvalidImageFileException("지원하지 않는 파일 형식입니다: " + file.getContentType());
        }
    }

    private String writeResultJson(ImageAnalysisResult result) {
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
```

- [ ] **Step 4: `AnalysisApiDelegateImpl.analyzeImage` 수정 — memberId 전달**

```java
    @Override
    public ResponseEntity<ImageAnalysisResponse> analyzeImage(MultipartFile file) {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        var result = imageAnalysisService.analyzeImage(file, memberId);
        return ResponseEntity.ok(AnalysisApiMapper.toResponse(result));
    }
```

- [ ] **Step 5: `AnalysisApiControllerTest.java`의 image 관련 테스트 4개 수정**

`when(imageAnalysisService.analyzeImage(any()))` → `when(imageAnalysisService.analyzeImage(any(), any()))`로 교체 (4곳: `analyzeImage_withAuthenticatedMemberAndValidFile_...`, `analyzeImage_withScamDetection_...`, `analyzeImage_withFileLargerThanSpringDefaultMultipartLimit_...`, 그리고 인증 없는 케이스는 `authenticatedMemberResolver`가 호출 전에 401을 던지므로 stub 불필요 — 그대로 둠).

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.ImageAnalysisServiceTest" --tests "com.veritae.veritae_server.api.AnalysisApiControllerTest"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add -u
git commit -m "feat(analysis): 이미지 분석 성공 시 AnalysisRecord로 회원별 저장"
```

---

## Task 5: `AudioAnalysisService`가 분석 성공 시 `AnalysisRecord`를 저장하도록 변경

Task 4와 완전히 동일한 패턴을 오디오에 적용한다.

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/analysis/AudioAnalysisService.java`
- Modify: `src/main/java/com/veritae/veritae_server/api/AnalysisApiDelegateImpl.java`
- Modify: `src/test/java/com/veritae/veritae_server/analysis/AudioAnalysisServiceTest.java`
- Modify: `src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java`

**Interfaces:**
- Consumes: `AnalysisRecord.completedSync`, `AnalysisRecordRepository.save` (Task 1, 2)
- Produces: `AudioAnalysisService.analyzeAudio(MultipartFile file, UUID memberId)` → `AudioAnalysisResult` (시그니처 변경)

- [ ] **Step 1: `AudioAnalysisServiceTest.java`에 memberId 인자 추가 + 저장 검증 테스트 추가**

기존 `AudioAnalysisServiceTest.java`를 읽고(Task 4의 `ImageAnalysisServiceTest` 패턴을 그대로 적용), `analyzeAudio(file)` 호출부를 전부 `analyzeAudio(file, memberId)`로 바꾸고, `AnalysisRecordRepository` mock 주입 + `Modality.AUDIO`로 저장되는지 검증하는 테스트를 추가한다. (Task 4 Step 1의 이미지 테스트와 동일한 구조 — `Modality.IMAGE` → `Modality.AUDIO`로만 교체)

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.AudioAnalysisServiceTest"`
Expected: FAIL

- [ ] **Step 3: `AudioAnalysisService.java` 수정**

Task 4 Step 3의 `ImageAnalysisService`와 동일한 구조로 작성한다 — `AudioAnalysisResult`, `Modality.AUDIO`, 기존 파일 검증 로직(포맷/용량 제한)은 그대로 유지하고 `analysisRecordRepository.save(...)` 호출만 추가한다.

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.AudioAnalysisResult;
import com.veritae.veritae_server.detection.AudioDetectionClient;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.analysisrecord.Modality;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AudioAnalysisService {

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp4", "audio/aac");
    private static final long MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024;

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final AudioDetectionClient audioDetectionClient;
    private final AnalysisRecordRepository analysisRecordRepository;

    public AudioAnalysisResult analyzeAudio(MultipartFile file, UUID memberId) {
        validate(file);
        AudioAnalysisResult result;
        try {
            result = audioDetectionClient.detectAudio(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            throw new UncheckedIOException("업로드된 파일을 읽을 수 없습니다.", e);
        }

        Double aiScore = result.aiDetection() != null ? result.aiDetection().score() : null;
        Double scamScore = result.scamDetection() != null ? result.scamDetection().score() : null;
        analysisRecordRepository.save(AnalysisRecord.completedSync(
                memberId, Modality.AUDIO, writeResultJson(result), aiScore, scamScore));

        return result;
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidAudioFileException("빈 파일은 분석할 수 없습니다.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new InvalidAudioFileException("지원하지 않는 파일 형식입니다: " + contentType);
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidAudioFileException("파일 용량이 25MB를 초과합니다.");
        }
    }

    private String writeResultJson(AudioAnalysisResult result) {
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
```

- [ ] **Step 4: `AnalysisApiDelegateImpl.analyzeAudio` 수정**

```java
    @Override
    public ResponseEntity<AudioAnalysisResponse> analyzeAudio(MultipartFile file) {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        var result = audioAnalysisService.analyzeAudio(file, memberId);
        return ResponseEntity.ok(AnalysisApiMapper.toAudioResponse(result));
    }
```

- [ ] **Step 5: `AnalysisApiControllerTest.java`의 audio 관련 테스트 수정**

`when(audioAnalysisService.analyzeAudio(any()))` → `when(audioAnalysisService.analyzeAudio(any(), any()))`.

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.AudioAnalysisServiceTest" --tests "com.veritae.veritae_server.api.AnalysisApiControllerTest"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add -u
git commit -m "feat(analysis): 음성 분석 성공 시 AnalysisRecord로 회원별 저장"
```

---

## Task 6: `openapi.yaml`에 이력 목록/리포트 엔드포인트 추가

**Files:**
- Modify: `src/main/resources/openapi.yaml`

**Interfaces:**
- Produces: openapi-generator가 생성할 `AnalysisApiDelegate.getAnalysisRecords(Integer page, Integer size)` → `ResponseEntity<AnalysisRecordListResponse>`, `getAnalysisReport()` → `ResponseEntity<AnalysisReportResponse>`, 및 모델 클래스 `AnalysisRecordListResponse`, `AnalysisRecordSummary`(중첩 enum `ModalityEnum` 포함), `AnalysisReportResponse`

- [ ] **Step 1: `paths:` 섹션에 두 경로 추가**

`/api/v1/analysis/jobs/{jobId}:` 블록(242~323번 줄) 바로 다음에 추가:

```yaml
  /api/v1/analysis/records:
    get:
      tags: [Analysis]
      operationId: getAnalysisRecords
      summary: 내 분석 기록 목록 조회
      description: >-
        로그인한 회원이 완료한 분석(이미지/음성/영상) 기록을 최신순으로 페이지 단위 반환한다.
        처리중/실패한 기록은 포함되지 않는다 — 영상 진행 상태 확인은
        GET /api/v1/analysis/jobs/{jobId}를 사용한다.
      security:
        - bearerAuth: []
      parameters:
        - name: page
          in: query
          required: false
          schema:
            type: integer
            format: int32
            minimum: 0
            default: 0
        - name: size
          in: query
          required: false
          schema:
            type: integer
            format: int32
            minimum: 1
            maximum: 50
            default: 10
      responses:
        '200':
          description: 조회 성공
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AnalysisRecordListResponse'
        '401':
          $ref: '#/components/responses/Unauthorized'
        default:
          $ref: '#/components/responses/ServerError'

  /api/v1/analysis/report:
    get:
      tags: [Analysis]
      operationId: getAnalysisReport
      summary: 내 분석 리포트(통계) 조회
      description: >-
        로그인한 회원의 완료된 분석 기록을 집계한 통계를 반환한다. aiDetectedCount/scamDetectedCount는
        점수 0.5 이상을 "탐지됨"으로 판단한 건수다.
      security:
        - bearerAuth: []
      responses:
        '200':
          description: 조회 성공
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AnalysisReportResponse'
        '401':
          $ref: '#/components/responses/Unauthorized'
        default:
          $ref: '#/components/responses/ServerError'
```

- [ ] **Step 2: `components.schemas`에 세 스키마 추가**

`AnalysisJobResponse` 스키마 블록(504~545번 줄) 바로 다음에 추가:

```yaml
    AnalysisRecordListResponse:
      type: object
      required: [content, page, size, totalElements, totalPages]
      properties:
        content:
          type: array
          nullable: false
          items:
            $ref: '#/components/schemas/AnalysisRecordSummary'
        page:
          type: integer
          format: int32
          nullable: false
          description: 현재 페이지 번호(0부터 시작).
          example: 0
        size:
          type: integer
          format: int32
          nullable: false
          example: 10
        totalElements:
          type: integer
          format: int64
          nullable: false
          example: 23
        totalPages:
          type: integer
          format: int32
          nullable: false
          example: 3

    AnalysisRecordSummary:
      type: object
      required: [id, modality, createdAt]
      description: >-
        분석 기록 한 건. modality에 따라 imageDetection/audioDetection/videoDetection 중
        해당하는 하나만 채워지고 나머지는 null이다.
      properties:
        id:
          type: string
          format: uuid
          nullable: false
          example: 11111111-1111-1111-1111-111111111111
        modality:
          type: string
          nullable: false
          enum: [IMAGE, AUDIO, VIDEO]
          example: IMAGE
        createdAt:
          type: string
          format: date-time
          nullable: false
          example: '2026-09-23T09:00:00Z'
        imageDetection:
          allOf:
            - $ref: '#/components/schemas/ImageDetectionResult'
          nullable: true
          description: modality가 IMAGE일 때만 채워진다.
        audioDetection:
          allOf:
            - $ref: '#/components/schemas/AudioDetectionResult'
          nullable: true
          description: modality가 AUDIO일 때만 채워진다.
        videoDetection:
          allOf:
            - $ref: '#/components/schemas/VideoDetectionResult'
          nullable: true
          description: modality가 VIDEO일 때만 채워진다. 얼굴없음 등으로 null일 수 있다.
        scamDetection:
          allOf:
            - $ref: '#/components/schemas/ScamDetectionResult'
          nullable: true
          description: 텍스트가 전혀 추출되지 않으면 null.

    AnalysisReportResponse:
      type: object
      required: [totalCount, imageCount, audioCount, videoCount, aiDetectedCount, scamDetectedCount]
      description: >-
        완료된 분석 기록 기준 집계. aiDetectedCount/scamDetectedCount는 점수 0.5 이상을
        "탐지됨"으로 판단한 건수다(고정 임계값, 추후 조정 가능).
      properties:
        totalCount:
          type: integer
          format: int64
          nullable: false
          example: 23
        imageCount:
          type: integer
          format: int64
          nullable: false
          example: 10
        audioCount:
          type: integer
          format: int64
          nullable: false
          example: 8
        videoCount:
          type: integer
          format: int64
          nullable: false
          example: 5
        aiDetectedCount:
          type: integer
          format: int64
          nullable: false
          description: AI 생성물로 판별된(aiScore >= 0.5) 건수.
          example: 3
        scamDetectedCount:
          type: integer
          format: int64
          nullable: false
          description: 사기 위험으로 판별된(scamScore >= 0.5) 건수.
          example: 2
```

- [ ] **Step 3: 코드 생성 확인**

Run: `./gradlew openApiGenerate` (또는 `./gradlew build` — 기존 빌드 설정에 generate가 물려있는지 확인 후)
Expected: BUILD SUCCESSFUL, `build/generated`(또는 프로젝트 설정된 경로) 하위에 `AnalysisApiDelegate`, `AnalysisRecordListResponse`, `AnalysisRecordSummary`, `AnalysisReportResponse` 클래스가 새로 생성됨

- [ ] **Step 4: 커밋**

```bash
git add src/main/resources/openapi.yaml
git commit -m "feat(analysis): openapi.yaml에 분석 기록 목록/리포트 조회 엔드포인트 추가"
```

---

## Task 7: `AnalysisApiMapper` — modality별 결과 역직렬화 + 이력/리포트 매핑

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/api/AnalysisApiMapper.java`
- Create: `src/test/java/com/veritae/veritae_server/api/AnalysisApiMapperTest.java`

**Interfaces:**
- Consumes: `AnalysisRecord`(Task 1), `AnalysisRecordSummary`/`AnalysisRecordListResponse`/`AnalysisReportResponse`(Task 6, openapi 생성 모델), `AnalysisHistoryService.AnalysisReportView`(Task 8 — 이 태스크에서는 필드 접근만 하므로 먼저 작성 가능)
- Produces: `AnalysisApiMapper.toRecordSummary(AnalysisRecord)` → `AnalysisRecordSummary`, `AnalysisApiMapper.toRecordListResponse(Page<AnalysisRecord>)` → `AnalysisRecordListResponse`, `AnalysisApiMapper.toReportResponse(AnalysisHistoryService.AnalysisReportView)` → `AnalysisReportResponse`

- [ ] **Step 1: 실패하는 테스트 작성** (`AnalysisApiMapperTest.java`)

```java
package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AnalysisHistoryService;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
import com.veritae.veritae_server.detection.ImageDetectionResult;
import com.veritae.veritae_server.detection.ScamDetectionResult;
import com.veritae.veritae_server.detection.ScamEvidence;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.Modality;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisApiMapperTest {

    @Test
    void toRecordSummary_withImageRecord_shouldFillImageDetectionOnly() throws Exception {
        var scam = new ScamDetectionResult("lilju", 0.82, List.of(new ScamEvidence("계좌번호를 알려주세요", 0.95)));
        var result = new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, "base64png"), scam);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var record = AnalysisRecord.completedSync(
                UUID.randomUUID(), Modality.IMAGE, objectMapper.writeValueAsString(result), 0.87, 0.82);

        var summary = AnalysisApiMapper.toRecordSummary(record);

        assertThat(summary.getModality().name()).isEqualTo("IMAGE");
        assertThat(summary.getImageDetection().getModel()).isEqualTo("spai");
        assertThat(summary.getImageDetection().getScore()).isEqualTo(0.87);
        assertThat(summary.getAudioDetection()).isNull();
        assertThat(summary.getVideoDetection()).isNull();
        assertThat(summary.getScamDetection().getScore()).isEqualTo(0.82);
    }

    @Test
    void toRecordListResponse_shouldMapPageMetadata() throws Exception {
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var result = new ImageAnalysisResult(new ImageDetectionResult("spai", 0.1, null), null);
        var record = AnalysisRecord.completedSync(
                UUID.randomUUID(), Modality.IMAGE, objectMapper.writeValueAsString(result), 0.1, null);
        var page = new PageImpl<>(List.of(record), PageRequest.of(1, 10), 23);

        var response = AnalysisApiMapper.toRecordListResponse(page);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getPage()).isEqualTo(1);
        assertThat(response.getSize()).isEqualTo(10);
        assertThat(response.getTotalElements()).isEqualTo(23);
        assertThat(response.getTotalPages()).isEqualTo(3);
    }

    @Test
    void toReportResponse_shouldMapAllCounts() {
        var view = new AnalysisHistoryService.AnalysisReportView(23, 10, 8, 5, 3, 2);

        var response = AnalysisApiMapper.toReportResponse(view);

        assertThat(response.getTotalCount()).isEqualTo(23);
        assertThat(response.getImageCount()).isEqualTo(10);
        assertThat(response.getAudioCount()).isEqualTo(8);
        assertThat(response.getVideoCount()).isEqualTo(5);
        assertThat(response.getAiDetectedCount()).isEqualTo(3);
        assertThat(response.getScamDetectedCount()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.api.AnalysisApiMapperTest"`
Expected: FAIL (메서드 없음, `AnalysisHistoryService.AnalysisReportView` 없음 — Task 8에서 만듦. 이 태스크에서는 컴파일만 되도록 `AnalysisReportView`를 먼저 최소 형태로 만들어도 되고, Task 8을 먼저 진행해도 된다 — 두 태스크는 서로 강하게 결합돼 있어 실제 구현 시 함께 다뤄도 무방)

- [ ] **Step 3: `AnalysisApiMapper.java`에 메서드 추가**

파일 상단에 `OBJECT_MAPPER` 상수와 import 추가, 클래스 하단에 세 메서드 추가:

```java
// 클래스 상단, private 생성자 아래에 추가
private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

// ... 기존 메서드들 ...

public static com.veritae.veritae_server.openapi.model.AnalysisRecordListResponse toRecordListResponse(
        org.springframework.data.domain.Page<com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord> page) {
    List<com.veritae.veritae_server.openapi.model.AnalysisRecordSummary> content =
            page.getContent().stream().map(AnalysisApiMapper::toRecordSummary).toList();
    return new com.veritae.veritae_server.openapi.model.AnalysisRecordListResponse(
            content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
}

public static com.veritae.veritae_server.openapi.model.AnalysisRecordSummary toRecordSummary(
        com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord record) {
    var modality = com.veritae.veritae_server.openapi.model.AnalysisRecordSummary.ModalityEnum
            .fromValue(record.getModality().name());
    var summary = new com.veritae.veritae_server.openapi.model.AnalysisRecordSummary(
            record.getId(), modality, record.getCreatedAt());

    switch (record.getModality()) {
        case IMAGE -> {
            ImageAnalysisResult result = readResult(record.getResultJson(), ImageAnalysisResult.class);
            summary.imageDetection(result.aiDetection() != null ? toOpenApiImageResult(result.aiDetection()) : null);
            summary.scamDetection(toOpenApiScamDetection(result.scamDetection()));
        }
        case AUDIO -> {
            AudioAnalysisResult result = readResult(record.getResultJson(), AudioAnalysisResult.class);
            summary.audioDetection(result.aiDetection() != null
                    ? new com.veritae.veritae_server.openapi.model.AudioDetectionResult(
                            result.aiDetection().model(), result.aiDetection().score(),
                            toOpenApiEvidence(result.aiDetection().evidence()))
                    : null);
            summary.scamDetection(toOpenApiScamDetection(result.scamDetection()));
        }
        case VIDEO -> {
            VideoAnalysisResult result = readResult(record.getResultJson(), VideoAnalysisResult.class);
            summary.videoDetection(result.aiDetection() != null ? toOpenApiResult(result.aiDetection()) : null);
            summary.scamDetection(toOpenApiScamDetection(result.scamDetection()));
        }
    }
    return summary;
}

public static com.veritae.veritae_server.openapi.model.AnalysisReportResponse toReportResponse(
        com.veritae.veritae_server.analysis.AnalysisHistoryService.AnalysisReportView view) {
    return new com.veritae.veritae_server.openapi.model.AnalysisReportResponse(
            view.totalCount(), view.imageCount(), view.audioCount(), view.videoCount(),
            view.aiDetectedCount(), view.scamDetectedCount());
}

private static com.veritae.veritae_server.openapi.model.ImageDetectionResult toOpenApiImageResult(
        ImageDetectionResult result) {
    return new com.veritae.veritae_server.openapi.model.ImageDetectionResult(result.model(), result.score())
            .evidenceImage(result.evidenceImage());
}

private static <T> T readResult(String resultJson, Class<T> type) {
    try {
        return OBJECT_MAPPER.readValue(resultJson, type);
    } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
    }
}
```

기존 `toResponse(ImageAnalysisResult result)` 메서드 안의 인라인 `ImageDetectionResult` 생성 코드(24~26번 줄)를 새로 만든 `toOpenApiImageResult(result.aiDetection())` 호출로 교체해 중복을 없앤다. `ImageDetectionResult`/`AudioAnalysisResult`/`VideoAnalysisResult` import 추가 필요.

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.api.AnalysisApiMapperTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -u src/test/java/com/veritae/veritae_server/api/AnalysisApiMapperTest.java
git commit -m "feat(analysis): AnalysisApiMapper에 modality별 결과 역직렬화 및 이력/리포트 매핑 추가"
```

---

## Task 8: `AnalysisHistoryService` — 이력 목록/리포트 조회 서비스

**Files:**
- Create: `src/main/java/com/veritae/veritae_server/analysis/AnalysisHistoryService.java`
- Create: `src/test/java/com/veritae/veritae_server/analysis/AnalysisHistoryServiceTest.java`

**Interfaces:**
- Consumes: `AnalysisRecordRepository`(Task 2)
- Produces: `AnalysisHistoryService.getRecords(UUID memberId, int page, int size)` → `Page<AnalysisRecord>`, `AnalysisHistoryService.getReport(UUID memberId)` → `AnalysisReportView`, `record AnalysisReportView(long totalCount, long imageCount, long audioCount, long videoCount, long aiDetectedCount, long scamDetectedCount)`

- [ ] **Step 1: 실패하는 테스트 작성** (`AnalysisHistoryServiceTest.java`)

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.analysisrecord.Modality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisHistoryServiceTest {

    @Mock
    private AnalysisRecordRepository analysisRecordRepository;

    private AnalysisHistoryService analysisHistoryService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        analysisHistoryService = new AnalysisHistoryService(analysisRecordRepository);
    }

    @Test
    void getRecords_shouldQueryCompletedRecordsForMemberWithPagination() {
        UUID memberId = UUID.randomUUID();
        AnalysisRecord record = AnalysisRecord.completedSync(memberId, Modality.IMAGE, "{}", 0.1, null);
        Page<AnalysisRecord> expected = new PageImpl<>(List.of(record));
        when(analysisRecordRepository.findByMemberIdAndStatusOrderByCreatedAtDesc(
                eq(memberId), eq(AnalysisJobStatus.COMPLETED), any(PageRequest.class)))
                .thenReturn(expected);

        Page<AnalysisRecord> result = analysisHistoryService.getRecords(memberId, 0, 10);

        assertThat(result.getContent()).containsExactly(record);
    }

    @Test
    void getReport_shouldAggregateCountsForMember() {
        UUID memberId = UUID.randomUUID();
        when(analysisRecordRepository.countByMemberIdAndStatus(memberId, AnalysisJobStatus.COMPLETED)).thenReturn(23L);
        when(analysisRecordRepository.countByMemberIdAndStatusAndModality(memberId, AnalysisJobStatus.COMPLETED, Modality.IMAGE)).thenReturn(10L);
        when(analysisRecordRepository.countByMemberIdAndStatusAndModality(memberId, AnalysisJobStatus.COMPLETED, Modality.AUDIO)).thenReturn(8L);
        when(analysisRecordRepository.countByMemberIdAndStatusAndModality(memberId, AnalysisJobStatus.COMPLETED, Modality.VIDEO)).thenReturn(5L);
        when(analysisRecordRepository.countByMemberIdAndStatusAndAiScoreGreaterThanEqual(memberId, AnalysisJobStatus.COMPLETED, 0.5)).thenReturn(3L);
        when(analysisRecordRepository.countByMemberIdAndStatusAndScamScoreGreaterThanEqual(memberId, AnalysisJobStatus.COMPLETED, 0.5)).thenReturn(2L);

        AnalysisHistoryService.AnalysisReportView view = analysisHistoryService.getReport(memberId);

        assertThat(view.totalCount()).isEqualTo(23);
        assertThat(view.imageCount()).isEqualTo(10);
        assertThat(view.audioCount()).isEqualTo(8);
        assertThat(view.videoCount()).isEqualTo(5);
        assertThat(view.aiDetectedCount()).isEqualTo(3);
        assertThat(view.scamDetectedCount()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.AnalysisHistoryServiceTest"`
Expected: FAIL (클래스 없음)

- [ ] **Step 3: `AnalysisHistoryService.java` 작성**

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord;
import com.veritae.veritae_server.domain.analysisrecord.AnalysisRecordRepository;
import com.veritae.veritae_server.domain.analysisrecord.Modality;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalysisHistoryService {

    // 리포트에서 "탐지됨"으로 셀 점수 기준선. 0~1 확률 중 과반(0.5) 이상을 탐지로 판단한다
    // (docs/superpowers/specs/2026-09-23-analysis-history-report-design.md — 고정값, 추후 조정 가능).
    private static final double RISK_THRESHOLD = 0.5;

    private final AnalysisRecordRepository analysisRecordRepository;

    public Page<AnalysisRecord> getRecords(UUID memberId, int page, int size) {
        return analysisRecordRepository.findByMemberIdAndStatusOrderByCreatedAtDesc(
                memberId, AnalysisJobStatus.COMPLETED, PageRequest.of(page, size, Sort.by("createdAt").descending()));
    }

    public AnalysisReportView getReport(UUID memberId) {
        long total = analysisRecordRepository.countByMemberIdAndStatus(memberId, AnalysisJobStatus.COMPLETED);
        long imageCount = analysisRecordRepository.countByMemberIdAndStatusAndModality(
                memberId, AnalysisJobStatus.COMPLETED, Modality.IMAGE);
        long audioCount = analysisRecordRepository.countByMemberIdAndStatusAndModality(
                memberId, AnalysisJobStatus.COMPLETED, Modality.AUDIO);
        long videoCount = analysisRecordRepository.countByMemberIdAndStatusAndModality(
                memberId, AnalysisJobStatus.COMPLETED, Modality.VIDEO);
        long aiDetectedCount = analysisRecordRepository.countByMemberIdAndStatusAndAiScoreGreaterThanEqual(
                memberId, AnalysisJobStatus.COMPLETED, RISK_THRESHOLD);
        long scamDetectedCount = analysisRecordRepository.countByMemberIdAndStatusAndScamScoreGreaterThanEqual(
                memberId, AnalysisJobStatus.COMPLETED, RISK_THRESHOLD);
        return new AnalysisReportView(total, imageCount, audioCount, videoCount, aiDetectedCount, scamDetectedCount);
    }

    public record AnalysisReportView(
            long totalCount, long imageCount, long audioCount, long videoCount,
            long aiDetectedCount, long scamDetectedCount) {
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.AnalysisHistoryServiceTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/analysis/AnalysisHistoryService.java src/test/java/com/veritae/veritae_server/analysis/AnalysisHistoryServiceTest.java
git commit -m "feat(analysis): 회원별 분석 이력/리포트 조회 서비스 추가"
```

---

## Task 9: 델리게이트/컨트롤러 연결 — 이력 목록 + 리포트 엔드포인트

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/api/AnalysisApiDelegateImpl.java`
- Modify: `src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java`

**Interfaces:**
- Consumes: `AnalysisHistoryService`(Task 8), `AnalysisApiMapper.toRecordListResponse`/`toReportResponse`(Task 7), openapi-generated `AnalysisApiDelegate.getAnalysisRecords`/`getAnalysisReport`(Task 6)
- Produces: `GET /api/v1/analysis/records`, `GET /api/v1/analysis/report` HTTP 엔드포인트

- [ ] **Step 1: 실패하는 컨트롤러 테스트 추가** (`AnalysisApiControllerTest.java`에 추가)

```java
    @MockitoBean
    private com.veritae.veritae_server.analysis.AnalysisHistoryService analysisHistoryService;

    @Test
    @WithMockUser
    void getAnalysisRecords_withAuthenticatedMember_shouldReturn200WithPagedContent() throws Exception {
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var result = new ImageAnalysisResult(new ImageDetectionResult("spai", 0.1, null), null);
        var record = com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord.completedSync(
                memberId, com.veritae.veritae_server.domain.analysisrecord.Modality.IMAGE,
                objectMapper.writeValueAsString(result), 0.1, null);
        var page = new org.springframework.data.domain.PageImpl<>(List.of(record));
        when(analysisHistoryService.getRecords(memberId, 0, 10)).thenReturn(page);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/analysis/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].modality").value("IMAGE"))
                .andExpect(jsonPath("$.content[0].imageDetection.model").value("spai"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getAnalysisRecords_withoutAuthentication_shouldReturn401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/analysis/records"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void getAnalysisReport_withAuthenticatedMember_shouldReturn200WithCounts() throws Exception {
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(analysisHistoryService.getReport(memberId)).thenReturn(
                new com.veritae.veritae_server.analysis.AnalysisHistoryService.AnalysisReportView(23, 10, 8, 5, 3, 2));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/analysis/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(23))
                .andExpect(jsonPath("$.scamDetectedCount").value(2));
    }

    @Test
    void getAnalysisReport_withoutAuthentication_shouldReturn401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/analysis/report"))
                .andExpect(status().isUnauthorized());
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.api.AnalysisApiControllerTest"`
Expected: FAIL (`AnalysisApiDelegateImpl`가 아직 새 델리게이트 메서드를 구현하지 않음)

- [ ] **Step 3: `AnalysisApiDelegateImpl.java`에 필드+메서드 추가**

```java
    private final com.veritae.veritae_server.analysis.AnalysisHistoryService analysisHistoryService;

    @Override
    public ResponseEntity<AnalysisRecordListResponse> getAnalysisRecords(Integer page, Integer size) {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        int resolvedPage = page != null ? page : 0;
        int resolvedSize = size != null ? size : 10;
        var records = analysisHistoryService.getRecords(memberId, resolvedPage, resolvedSize);
        return ResponseEntity.ok(AnalysisApiMapper.toRecordListResponse(records));
    }

    @Override
    public ResponseEntity<AnalysisReportResponse> getAnalysisReport() {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        var report = analysisHistoryService.getReport(memberId);
        return ResponseEntity.ok(AnalysisApiMapper.toReportResponse(report));
    }
```

`@RequiredArgsConstructor`를 쓰므로 필드 추가만으로 생성자 주입이 이뤄진다. `import com.veritae.veritae_server.openapi.model.AnalysisRecordListResponse;`, `import com.veritae.veritae_server.openapi.model.AnalysisReportResponse;` 추가.

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.api.AnalysisApiControllerTest"`
Expected: PASS

- [ ] **Step 5: 전체 빌드 + 전체 테스트 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add -u
git commit -m "feat(analysis): 분석 이력 목록/리포트 조회 API 엔드포인트 연결"
```

---

## 최종 확인 (수동)

- [ ] IntelliJ에서 서버 기동 후 Swagger UI(`/swagger-ui.html`)에서 `GET /api/v1/analysis/records`, `GET /api/v1/analysis/report`가 노출되는지 확인 (사용자가 직접 실행 — [[feedback_dont_run_app_for_user]])
- [ ] 실제 로그인 토큰으로 이미지 분석 1회 호출 후, `GET /api/v1/analysis/records`에 방금 분석한 기록이 나오는지 확인
- [ ] 같은 토큰으로 `GET /api/v1/analysis/report`의 `totalCount`가 1 이상 증가했는지 확인
