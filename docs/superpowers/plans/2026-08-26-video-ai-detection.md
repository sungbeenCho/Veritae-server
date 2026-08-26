# 영상 AI 판독(얼굴조작 딥페이크 탐지) 기능 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /api/v1/analysis/video`로 영상을 업로드하면 비동기 분석 작업(job)이 접수되고, `GET /api/v1/analysis/jobs/{jobId}`로 폴링해 얼굴조작(face-swap) 딥페이크 여부 점수와 판독 근거(시간 구간 + best-effort 히트맵)를 받는다.

**Architecture:** iOS 앱 → Spring(`veritae-server`, 이 레포, 오케스트레이터) → Python 탐지 서버(`veritae-detection-server`, 3060Ti 데스크탑). 이미지/음성과 달리 영상은 처리 시간을 예측하기 어려워 처음부터 비동기 잡 모델(`AnalysisJob`, DB 저장)로 간다. Python 쪽은 SPAI/AntiDeepfake와 동일하게 무거운 모델 의존성(opencv-python, facenet-pytorch 등)을 별도 conda env(`dfdc`)에 격리하고, 가벼운 FastAPI 서버(`detection-api` env)가 subprocess + 파일로 결과를 주고받는다.

**Tech Stack:** Spring Boot(Java, `@Async`), openapi-generator(delegate pattern), Spring Data JPA(MySQL), FastAPI(Python), selimsef/dfdc_deepfake_challenge(`tf_efficientnet_b7_ns` 단일 체크포인트), opencv-python, facenet-pytorch(MTCNN), pytorch-grad-cam(best-effort).

**Spec:** `docs/superpowers/specs/2026-08-26-video-ai-detection-design.md`

## Global Constraints

- 이번 스코프는 **(A) 얼굴조작(face-swap) 딥페이크만** 다룬다 — (B) 완전 생성형 영상(Sora류)은 명시적으로 스코프 밖.
- AI판독(생성 여부) 이유만 다룬다 — 사기감지(fraud-risk) 분석은 완전히 별도이며 이번 작업에 포함하지 않는다.
- 영상은 **비동기 잡 모델**(`AnalysisJob`, DB 저장)로 간다 — 이미지/음성의 기존 sync `DetectionClient`/`AudioDetectionClient`/`ImageAnalysisService`/`AudioAnalysisService`는 전혀 건드리지 않는다.
- Evidence는 시간 구간(temporal, 필수) + 공간적 히트맵(`evidenceImage`, best-effort — 안 되면 null)을 지원한다. 히트맵이 실제로 되는지는 **데스크탑에서 검증 전** — 실패해도 시간 구간 evidence는 항상 나온다.
- Python 쪽 무거운 의존성(opencv-python, facenet-pytorch, pytorch-grad-cam 등)은 `dfdc` conda env에 격리한다 — `detection-api` env(FastAPI)는 가볍게 유지하고 새 의존성을 추가하지 않는다.
- Claude Code 세션은 이 노트북(veritae-server, veritae-detection-server 로컬 클론)에서만 코드를 작성할 수 있다 — 3060Ti 데스크탑 실행/검증은 사용자가 직접 한다. `scripts/dfdc_infer.py`(Task 10)는 GPU/모델 없이는 이 세션에서 실행/테스트할 수 없다 — SPAI/AntiDeepfake 때와 동일하게, 로직 검증은 subprocess를 모킹하는 `dfdc_runner.py` 테스트(Task 8)로 하고, 스크립트 자체의 실제 동작은 데스크탑에서 확인한다.

---

## Task 1: OpenAPI 계약 확장 — AnalysisJob 스키마 + evidenceImage 필드 + 영상 경로 2개

**Files:**
- Modify: `src/main/resources/openapi.yaml`

**Interfaces:**
- Consumes: 없음
- Produces: 코드 생성 결과 `com.veritae.veritae_server.openapi.model.AiDetectionResult(String model, Double score, List<Evidence> evidence, String evidenceImage)`(4번째 필드 추가), `AnalysisJobAcceptedResponse(UUID jobId)`, `AnalysisJobResponse(UUID jobId, String status, AiDetectionResult aiDetection, String errorMessage)`, `AnalysisApiDelegate.analyzeVideo(MultipartFile file)`/`getAnalysisJob(UUID jobId)`(default 501 메서드) — Task 2, 4, 7이 이 타입/메서드를 사용한다.

- [ ] **Step 1: `AiDetectionResult` 스키마에 `evidenceImage` 필드 추가**

`src/main/resources/openapi.yaml`의 `AiDetectionResult` 스키마(`evidence:` 항목 다음)에 추가:

```yaml
        evidenceImage:
          type: string
          nullable: true
          description: >-
            판독 근거 히트맵 이미지(base64 인코딩 PNG). 얼굴의 어느 부분이 의심스러운지
            시각적으로 보여준다. 이미지/음성 분석에서는 채워지지 않아 항상 null.
```

(전체 `AiDetectionResult` 블록은 아래와 같은 모양이 된다 — `required`는 그대로 `[model, score, evidence]`, `evidenceImage`는 선택 필드이므로 required에 넣지 않는다)

```yaml
    AiDetectionResult:
      type: object
      required: [model, score, evidence]
      properties:
        model:
          type: string
          nullable: false
          description: 탐지에 사용된 모델 이름.
          example: spai
        score:
          type: number
          format: double
          nullable: false
          minimum: 0
          maximum: 1
          description: AI 생성물일 확률(0~1). 1에 가까울수록 AI 생성 가능성이 높다.
          example: 0.000134
        evidence:
          type: array
          nullable: false
          description: 판독 근거 카드 목록. 근거가 없으면 빈 배열.
          items:
            $ref: '#/components/schemas/Evidence'
        evidenceImage:
          type: string
          nullable: true
          description: >-
            판독 근거 히트맵 이미지(base64 인코딩 PNG). 얼굴의 어느 부분이 의심스러운지
            시각적으로 보여준다. 이미지/음성 분석에서는 채워지지 않아 항상 null.
```

- [ ] **Step 2: `AnalysisJobAcceptedResponse`, `AnalysisJobResponse` 스키마 신규 추가**

`AudioAnalysisResponse` 스키마 바로 다음에 추가:

```yaml
    AnalysisJobAcceptedResponse:
      type: object
      required: [jobId]
      properties:
        jobId:
          type: string
          format: uuid
          nullable: false
          description: 생성된 분석 작업 ID. GET /api/v1/analysis/jobs/{jobId}로 상태를 조회한다.
          example: 11111111-1111-1111-1111-111111111111

    AnalysisJobResponse:
      type: object
      required: [jobId, status]
      properties:
        jobId:
          type: string
          format: uuid
          nullable: false
          example: 11111111-1111-1111-1111-111111111111
        status:
          type: string
          nullable: false
          enum: [PENDING, PROCESSING, COMPLETED, FAILED]
          description: 작업 처리 상태.
          example: COMPLETED
        aiDetection:
          allOf:
            - $ref: '#/components/schemas/AiDetectionResult'
          nullable: true
          description: status가 COMPLETED일 때만 채워진다.
        errorMessage:
          type: string
          nullable: true
          description: status가 FAILED일 때만 채워진다.
```

- [ ] **Step 3: `/api/v1/analysis/video`, `/api/v1/analysis/jobs/{jobId}` 경로 추가**

`paths:` 블록의 `/api/v1/analysis/audio:` 항목 바로 다음(`components:` 앞)에 추가:

```yaml
  /api/v1/analysis/video:
    post:
      tags: [Analysis]
      operationId: analyzeVideo
      summary: 영상 AI 생성 여부(얼굴조작 딥페이크) 분석 요청
      description: >-
        업로드한 영상의 얼굴조작(face-swap) 딥페이크 여부 분석을 비동기 작업으로 접수한다.
        처리에 수십 초~수 분이 걸릴 수 있어 즉시 202로 jobId를 반환하고, 결과는
        GET /api/v1/analysis/jobs/{jobId}로 폴링해 확인한다. 완전 생성형(Sora류) 영상 탐지는
        지원하지 않는다 - 얼굴조작 딥페이크만 판독한다.
      security:
        - bearerAuth: []
      requestBody:
        required: true
        content:
          multipart/form-data:
            schema:
              type: object
              required: [file]
              properties:
                file:
                  type: string
                  format: binary
                  description: 분석할 영상 파일(mp4/mov/avi, 최대 100MB).
      responses:
        '202':
          description: 분석 작업 접수됨
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AnalysisJobAcceptedResponse'
        '400':
          $ref: '#/components/responses/ValidationError'
        '401':
          $ref: '#/components/responses/Unauthorized'
        default:
          $ref: '#/components/responses/ServerError'

  /api/v1/analysis/jobs/{jobId}:
    get:
      tags: [Analysis]
      operationId: getAnalysisJob
      summary: 분석 작업 상태/결과 조회
      description: >-
        POST /api/v1/analysis/video 로 접수한 작업의 현재 상태를 조회한다. status가
        COMPLETED일 때만 aiDetection이, FAILED일 때만 errorMessage가 채워진다. 본인이
        접수한 작업이 아니면 404를 반환한다(다른 사용자 작업의 존재 여부를 숨기기 위함).
      security:
        - bearerAuth: []
      parameters:
        - name: jobId
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: 조회 성공
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AnalysisJobResponse'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '404':
          $ref: '#/components/responses/AnalysisJobNotFound'
        default:
          $ref: '#/components/responses/ServerError'
```

- [ ] **Step 4: `AnalysisJobNotFound` 응답 신규 추가**

`components: responses:` 블록의 `DetectionServiceUnavailable:` 다음에 추가:

```yaml
    AnalysisJobNotFound:
      description: 분석 작업을 찾을 수 없거나 본인 소유가 아님
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/ProblemDetail'
          example:
            type: https://api.veritae.app/errors/analysis-job-not-found
            title: Analysis Job Not Found
            status: 404
            detail: 분석 작업을 찾을 수 없습니다.
            instance: /api/v1/analysis/jobs/11111111-1111-1111-1111-111111111111
            errorCode: ANALYSIS_JOB_NOT_FOUND
            timestamp: '2026-08-26T09:00:00Z'
```

- [ ] **Step 5: 빌드해서 코드 재생성 + 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL — `AiDetectionResult.java`에 `evidenceImage` 필드 추가됨, `AnalysisJobAcceptedResponse.java`/`AnalysisJobResponse.java` 생성됨, `AnalysisApiDelegate.java`에 `analyzeVideo`/`getAnalysisJob` default 메서드(501) 추가됨. 기존 `AnalysisApiMapper`/`AnalysisApiDelegateImpl`은 아직 4-arg 생성자를 안 써서 컴파일 에러가 날 수 있다 — Task 2에서 고친다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/resources/openapi.yaml
git commit -m "feat(api): 영상 분석 API 계약 추가 (AnalysisJob 스키마, evidenceImage 필드, POST /api/v1/analysis/video, GET /api/v1/analysis/jobs/{jobId})"
```

---

## Task 2: `AiDetectionResult`에 `evidenceImage` 필드 추가 (기존 코드 갱신)

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/detection/AiDetectionResult.java`
- Modify: `src/main/java/com/veritae/veritae_server/detection/spai/SpaiHttpDetectionClient.java`
- Modify: `src/main/java/com/veritae/veritae_server/detection/antideepfake/AntiDeepfakeHttpDetectionClient.java`
- Modify: `src/main/java/com/veritae/veritae_server/api/AnalysisApiMapper.java`
- Modify: `src/test/java/com/veritae/veritae_server/analysis/ImageAnalysisServiceTest.java`
- Modify: `src/test/java/com/veritae/veritae_server/analysis/AudioAnalysisServiceTest.java`
- Modify: `src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java`

**Interfaces:**
- Consumes: Task 1이 만든 4-필드 `com.veritae.veritae_server.openapi.model.AiDetectionResult`
- Produces: `com.veritae.veritae_server.detection.AiDetectionResult(String model, double score, List<Evidence> evidence, String evidenceImage)`(4-arg로 변경) — Task 4, 6, 7이 이 타입을 사용한다.

- [ ] **Step 1: `AiDetectionResult`에 `evidenceImage` 필드 추가**

`src/main/java/com/veritae/veritae_server/detection/AiDetectionResult.java` 전체를 아래로 교체:

```java
package com.veritae.veritae_server.detection;

import java.util.List;

/**
 * evidenceImage는 판독 근거 히트맵(base64 PNG)이다. 이미지/음성 어댑터는 아직 히트맵을
 * 만들지 않아 항상 null을 넘긴다 - 영상(DfdcHttpDetectionClient)만 채운다.
 */
public record AiDetectionResult(String model, double score, List<Evidence> evidence, String evidenceImage) {
}
```

- [ ] **Step 2: 기존 어댑터 호출부를 4-arg 생성자로 업데이트**

`src/main/java/com/veritae/veritae_server/detection/spai/SpaiHttpDetectionClient.java`의 아래 줄을:

```java
            return new AiDetectionResult(response.aiDetection().model(), response.aiDetection().score(), List.of());
```

이렇게 바꾼다:

```java
            return new AiDetectionResult(response.aiDetection().model(), response.aiDetection().score(), List.of(), null);
```

`src/main/java/com/veritae/veritae_server/detection/antideepfake/AntiDeepfakeHttpDetectionClient.java`의 아래 줄을:

```java
            return new AiDetectionResult(response.aiDetection().model(), response.aiDetection().score(), evidence);
```

이렇게 바꾼다:

```java
            return new AiDetectionResult(response.aiDetection().model(), response.aiDetection().score(), evidence, null);
```

- [ ] **Step 3: `AnalysisApiMapper`가 evidenceImage를 openapi 모델로 매핑하도록 수정**

`src/main/java/com/veritae/veritae_server/api/AnalysisApiMapper.java`의 아래 줄을:

```java
        return new com.veritae.veritae_server.openapi.model.AiDetectionResult(result.model(), result.score(), evidence);
```

이렇게 바꾼다:

```java
        return new com.veritae.veritae_server.openapi.model.AiDetectionResult(
                result.model(), result.score(), evidence, result.evidenceImage());
```

- [ ] **Step 4: 테스트 코드의 생성자 호출부 전부 업데이트**

`src/test/java/com/veritae/veritae_server/analysis/ImageAnalysisServiceTest.java` — `new AiDetectionResult("spai", 0.87, List.of())`를 `new AiDetectionResult("spai", 0.87, List.of(), null)`로 교체.

`src/test/java/com/veritae/veritae_server/analysis/AudioAnalysisServiceTest.java` — `new AiDetectionResult("antideepfake", 0.87, List.of())`를 `new AiDetectionResult("antideepfake", 0.87, List.of(), null)`로 교체.

`src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java` — 아래 3곳을 각각 교체:

50번째 줄 근처, 69번째 줄 근처 (동일 패턴 2곳):
```java
        when(imageAnalysisService.analyzeImage(any())).thenReturn(new AiDetectionResult("spai", 0.87, List.of()));
```
→
```java
        when(imageAnalysisService.analyzeImage(any())).thenReturn(new AiDetectionResult("spai", 0.87, List.of(), null));
```

87번째 줄 근처:
```java
        when(audioAnalysisService.analyzeAudio(any())).thenReturn(new AiDetectionResult(
                "antideepfake", 0.87,
                List.of(new Evidence(
                        "시간 구간 이상 패턴", "0.5초~1.2초 구간에서 합성 흔적이 감지됨",
                        List.of("temporal"), 0.5, 1.2))));
```
→
```java
        when(audioAnalysisService.analyzeAudio(any())).thenReturn(new AiDetectionResult(
                "antideepfake", 0.87,
                List.of(new Evidence(
                        "시간 구간 이상 패턴", "0.5초~1.2초 구간에서 합성 흔적이 감지됨",
                        List.of("temporal"), 0.5, 1.2)),
                null));
```

- [ ] **Step 5: 빌드 + 전체 테스트 실행해서 통과 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 전체 테스트 PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/detection/AiDetectionResult.java src/main/java/com/veritae/veritae_server/detection/spai/SpaiHttpDetectionClient.java src/main/java/com/veritae/veritae_server/detection/antideepfake/AntiDeepfakeHttpDetectionClient.java src/main/java/com/veritae/veritae_server/api/AnalysisApiMapper.java src/test/java/com/veritae/veritae_server/analysis/ImageAnalysisServiceTest.java src/test/java/com/veritae/veritae_server/analysis/AudioAnalysisServiceTest.java src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java
git commit -m "feat(detection): AiDetectionResult에 evidenceImage 필드 추가 (영상 히트맵용, 기존 이미지/음성은 항상 null)"
```

---

## Task 3: `AnalysisJob` 엔티티 + Repository + 상태 enum + NotFoundException

**Files:**
- Create: `src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJob.java`
- Create: `src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobStatus.java`
- Create: `src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobRepository.java`
- Create: `src/main/java/com/veritae/veritae_server/analysis/AnalysisJobNotFoundException.java`
- Create: `src/test/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `AnalysisJob.submit(UUID memberId): AnalysisJob`(정적 팩토리), `AnalysisJob.markProcessing()`/`markCompleted(String resultJson)`/`markFailed(String errorMessage)`, `AnalysisJob.getId()`/`getMemberId()`/`getStatus()`/`getResultJson()`/`getErrorMessage()`, `AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID>`, `AnalysisJobStatus`(enum), `AnalysisJobNotFoundException` — Task 6, 7이 사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobTest.java`:

```java
package com.veritae.veritae_server.domain.analysisjob;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisJobTest {

    @Test
    void submit_shouldCreatePendingJob() {
        UUID memberId = UUID.randomUUID();

        AnalysisJob job = AnalysisJob.submit(memberId);

        assertThat(job.getId()).isNotNull();
        assertThat(job.getMemberId()).isEqualTo(memberId);
        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        assertThat(job.getResultJson()).isNull();
        assertThat(job.getErrorMessage()).isNull();
    }

    @Test
    void markProcessing_shouldTransitionToProcessing() {
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());

        job.markProcessing();

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
    }

    @Test
    void markCompleted_shouldTransitionToCompletedAndStoreResult() {
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());

        job.markCompleted("{\"score\":0.87}");

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(job.getResultJson()).isEqualTo("{\"score\":0.87}");
    }

    @Test
    void markFailed_shouldTransitionToFailedAndStoreErrorMessage() {
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());

        job.markFailed("탐지 서버 호출 실패");

        assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("탐지 서버 호출 실패");
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.domain.analysisjob.AnalysisJobTest"`
Expected: FAIL — `AnalysisJob`/`AnalysisJobStatus` 클래스가 없어 컴파일 에러

- [ ] **Step 3: `AnalysisJobStatus` enum 작성**

`src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobStatus.java`:

```java
package com.veritae.veritae_server.domain.analysisjob;

public enum AnalysisJobStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
```

- [ ] **Step 4: `AnalysisJob` 엔티티 작성**

`src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJob.java`:

```java
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
```

- [ ] **Step 5: `AnalysisJobRepository` 작성**

`src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobRepository.java`:

```java
package com.veritae.veritae_server.domain.analysisjob;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {
}
```

- [ ] **Step 6: `AnalysisJobNotFoundException` 작성**

`src/main/java/com/veritae/veritae_server/analysis/AnalysisJobNotFoundException.java`:

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * 존재하지 않거나 다른 회원 소유인 job을 조회하려 한 경우. 소유자가 아니어도 404로
 * 통일한다(403 대신) - 다른 사용자 job의 존재 여부 자체를 숨기기 위함.
 */
public class AnalysisJobNotFoundException extends DomainException {

    public AnalysisJobNotFoundException(UUID jobId) {
        super("ANALYSIS_JOB_NOT_FOUND", HttpStatus.NOT_FOUND, "Analysis Job Not Found",
                "분석 작업을 찾을 수 없습니다: " + jobId);
    }
}
```

- [ ] **Step 7: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.domain.analysisjob.AnalysisJobTest"`
Expected: PASS (4개 전부)

- [ ] **Step 8: 전체 테스트 스위트 실행 (JPA 엔티티가 실제 스키마와 맞는지 확인 포함)**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — `spring.jpa.hibernate.ddl-auto=update` 설정이라 애플리케이션 컨텍스트가 뜨는 테스트(`@WebMvcTest`는 DB 안 붙으므로 무관, 이 태스크는 순수 단위 테스트만 추가했으므로 DB 연결 자체가 필요 없음)에서도 문제없이 통과해야 한다.

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJob.java src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobStatus.java src/main/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobRepository.java src/main/java/com/veritae/veritae_server/analysis/AnalysisJobNotFoundException.java src/test/java/com/veritae/veritae_server/domain/analysisjob/AnalysisJobTest.java
git commit -m "feat(domain): AnalysisJob 엔티티 추가 (영상 비동기 분석 작업 상태 관리)"
```

---

## Task 4: `VideoDetectionClient` 인터페이스 + `DfdcHttpDetectionClient` 어댑터 (+ 전용 긴 타임아웃 RestClient)

**Files:**
- Create: `src/main/java/com/veritae/veritae_server/detection/VideoDetectionClient.java`
- Create: `src/main/java/com/veritae/veritae_server/detection/dfdc/DfdcHttpDetectionClient.java`
- Create: `src/test/java/com/veritae/veritae_server/detection/dfdc/DfdcHttpDetectionClientTest.java`
- Modify: `src/main/java/com/veritae/veritae_server/detection/DetectionProperties.java`
- Modify: `src/main/java/com/veritae/veritae_server/config/DetectionClientConfig.java`
- Modify: `src/main/resources/application.properties`

**Interfaces:**
- Consumes: Task 2의 `detection.AiDetectionResult`/`detection.Evidence`
- Produces: `detection.VideoDetectionClient.detectVideo(byte[] videoBytes, String filename, String contentType): AiDetectionResult` — Task 6이 사용한다.

**왜 전용 RestClient가 필요한가:** 이미지/음성이 쓰는 `detectionRestClient` 빈은 `detection.read-timeout=300s`로 묶여있다. 영상은 비동기라 사용자를 기다리게 하지 않으므로 훨씬 넉넉한 타임아웃을 줘도 되는데, 그렇다고 공용 빈의 타임아웃을 늘리면 이미지/음성에도 불필요하게 영향을 준다. 그래서 영상 전용 `videoDetectionRestClient` 빈을 따로 만든다(base URL은 동일 - 같은 탐지 서버).

- [ ] **Step 1: `DetectionProperties`에 영상 전용 타임아웃 필드 추가**

`src/main/java/com/veritae/veritae_server/detection/DetectionProperties.java` 전체를 아래로 교체:

```java
package com.veritae.veritae_server.detection;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * detection.* 설정. serviceUrl 은 탐지 서버(3060Ti 데스크탑에서 상시 구동되는
 * veritae-detection-server)의 LAN 주소다. 고정 IP가 아니면 데스크탑 재부팅 시 바뀔 수 있다.
 * videoReadTimeout 은 이미지/음성과 별도 - 영상은 비동기라 사용자를 기다리게 하지 않으므로
 * 훨씬 넉넉하게 잡는다(초기 추정치, 실측 후 조정 필요 - 오디오 때도 같은 이유로 조정했었음).
 */
@ConfigurationProperties(prefix = "detection")
public record DetectionProperties(String serviceUrl, Duration connectTimeout, Duration readTimeout,
                                   Duration videoReadTimeout) {
}
```

- [ ] **Step 2: `DetectionClientConfig`에 영상 전용 RestClient 빈 추가**

`src/main/java/com/veritae/veritae_server/config/DetectionClientConfig.java` 전체를 아래로 교체:

```java
package com.veritae.veritae_server.config;

import com.veritae.veritae_server.detection.DetectionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 탐지 서버(veritae-detection-server) 호출용 RestClient. base-url 과 timeout 은
 * detection.* 설정(DetectionProperties)에서 가져온다. videoDetectionRestClient 는
 * detectionRestClient 와 base URL(같은 탐지 서버)은 같지만 read-timeout 만 훨씬 길다 -
 * 영상은 비동기 처리라 이미지/음성의 sync 타임아웃 예산에 영향을 주면 안 되기 때문에
 * 별도 빈으로 분리했다.
 */
@Configuration
@RequiredArgsConstructor
public class DetectionClientConfig {

    private final DetectionProperties detectionProperties;

    @Bean
    public RestClient detectionRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) detectionProperties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) detectionProperties.readTimeout().toMillis());

        return RestClient.builder()
                .baseUrl(detectionProperties.serviceUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    public RestClient videoDetectionRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) detectionProperties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) detectionProperties.videoReadTimeout().toMillis());

        return RestClient.builder()
                .baseUrl(detectionProperties.serviceUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
```

- [ ] **Step 3: `application.properties`에 `detection.video-read-timeout` 추가**

`src/main/resources/application.properties`의 `detection.read-timeout=300s` 다음 줄에 추가:

```properties
# 영상 분석은 비동기(사용자를 기다리게 하지 않음)라 이미지/음성보다 훨씬 넉넉하게 잡는다.
# 실측 데이터 없음(2026-08-26 기준) - 얼굴검출+CNN 추론이 여러 프레임에 걸쳐 겹쳐 오디오보다
# 오래 걸릴 걸로 예상되나 정확한 값은 데스크탑 실측 후 조정 필요.
detection.video-read-timeout=20m
```

- [ ] **Step 4: 실패하는 테스트 작성**

`src/test/java/com/veritae/veritae_server/detection/dfdc/DfdcHttpDetectionClientTest.java`:

```java
package com.veritae.veritae_server.detection.dfdc;

import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.detection.DetectionServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DfdcHttpDetectionClientTest {

    @Test
    void detectVideo_withSuccessResponse_shouldParseScoreEvidenceAndEvidenceImage() {
        // Given
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/video"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        """
                        {"ai_detection": {"model": "dfdc", "score": 0.91, "evidence_image": "base64pngdata", "evidence": [
                            {"title": "얼굴 조작 의심 구간", "description": "3.0초~7.0초 구간에서 얼굴 합성 흔적이 감지됨",
                             "tags": ["temporal", "face-swap"], "start_sec": 3.0, "end_sec": 7.0}
                        ]}}
                        """,
                        MediaType.APPLICATION_JSON));
        DfdcHttpDetectionClient client = new DfdcHttpDetectionClient(builder.build());

        // When
        AiDetectionResult result = client.detectVideo("fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        assertThat(result.model()).isEqualTo("dfdc");
        assertThat(result.score()).isEqualTo(0.91);
        assertThat(result.evidenceImage()).isEqualTo("base64pngdata");
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0).title()).isEqualTo("얼굴 조작 의심 구간");
        assertThat(result.evidence().get(0).startSec()).isEqualTo(3.0);
        server.verify();
    }

    @Test
    void detectVideo_withNullEvidenceImage_shouldReturnNullEvidenceImage() {
        // Given: 히트맵 생성이 실패한 경우(Grad-CAM best-effort 실패) evidence_image가 null로 옴
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/video"))
                .andRespond(withSuccess(
                        """
                        {"ai_detection": {"model": "dfdc", "score": 0.12, "evidence_image": null, "evidence": []}}
                        """,
                        MediaType.APPLICATION_JSON));
        DfdcHttpDetectionClient client = new DfdcHttpDetectionClient(builder.build());

        // When
        AiDetectionResult result = client.detectVideo("fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        assertThat(result.evidenceImage()).isNull();
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void detectVideo_whenServerReturnsError_shouldThrowDetectionServiceException() {
        // Given
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/video"))
                .andRespond(withServerError());
        DfdcHttpDetectionClient client = new DfdcHttpDetectionClient(builder.build());

        // When / Then
        assertThatThrownBy(() -> client.detectVideo("fake-bytes".getBytes(), "test.mp4", "video/mp4"))
                .isInstanceOf(DetectionServiceException.class);
    }
}
```

- [ ] **Step 5: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.detection.dfdc.DfdcHttpDetectionClientTest"`
Expected: FAIL — `VideoDetectionClient`/`DfdcHttpDetectionClient` 클래스가 없어 컴파일 에러

- [ ] **Step 6: `VideoDetectionClient` 인터페이스 작성**

`src/main/java/com/veritae/veritae_server/detection/VideoDetectionClient.java`:

```java
package com.veritae.veritae_server.detection;

/**
 * 영상 얼굴조작(face-swap) 딥페이크 탐지 요청을 추상화하는 포트. 이미지/음성과 별도
 * 인터페이스로 분리한 이유는 docs/superpowers/specs/2026-08-18-audio-ai-detection-design.md §4
 * 참고(영상이 비동기 패턴을 갖게 되어 하나의 인터페이스에 다 몰아넣는 게 부담이 됨 - 실제로
 * 이번에 그 비동기화가 일어났다).
 */
public interface VideoDetectionClient {

    AiDetectionResult detectVideo(byte[] videoBytes, String filename, String contentType);
}
```

- [ ] **Step 7: `DfdcHttpDetectionClient` 어댑터 작성**

`src/main/java/com/veritae/veritae_server/detection/dfdc/DfdcHttpDetectionClient.java`:

```java
package com.veritae.veritae_server.detection.dfdc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.Evidence;
import com.veritae.veritae_server.detection.VideoDetectionClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * {@link VideoDetectionClient} 의 selimsef/dfdc_deepfake_challenge(셀프호스팅) 구현체.
 * 탐지 서버(veritae-detection-server, 3060Ti 데스크탑)의 POST /process/video 를 호출한다.
 * videoDetectionRestClient 빈(전용 긴 타임아웃)을 쓴다 - DetectionClientConfig 참고.
 * 같은 타입(RestClient) 빈이 2개(detectionRestClient, videoDetectionRestClient) 있어
 * @RequiredArgsConstructor 대신 @Qualifier가 붙은 생성자를 명시적으로 쓴다(같이 쓰면
 * Lombok이 똑같은 시그니처의 생성자를 하나 더 만들어 중복 생성자 컴파일 에러가 난다).
 */
@Component
public class DfdcHttpDetectionClient implements VideoDetectionClient {

    private final RestClient videoDetectionRestClient;

    public DfdcHttpDetectionClient(@Qualifier("videoDetectionRestClient") RestClient videoDetectionRestClient) {
        this.videoDetectionRestClient = videoDetectionRestClient;
    }

    @Override
    public AiDetectionResult detectVideo(byte[] videoBytes, String filename, String contentType) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(videoBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, MediaType.parseMediaType(contentType));

        try {
            DfdcResponse response = videoDetectionRestClient.post()
                    .uri("/process/video")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(builder.build())
                    .retrieve()
                    .body(DfdcResponse.class);

            if (response == null || response.aiDetection() == null) {
                throw new DetectionServiceException("탐지 서버 응답이 비어 있습니다.", null);
            }

            List<Evidence> evidence = response.aiDetection().evidence().stream()
                    .map(e -> new Evidence(e.title(), e.description(), e.tags(), e.startSec(), e.endSec()))
                    .toList();
            return new AiDetectionResult(
                    response.aiDetection().model(),
                    response.aiDetection().score(),
                    evidence,
                    response.aiDetection().evidenceImage());
        } catch (RestClientException e) {
            throw new DetectionServiceException("탐지 서버 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }

    private record DfdcResponse(@JsonProperty("ai_detection") AiDetection aiDetection) {
    }

    private record AiDetection(
            String model,
            double score,
            List<EvidenceDto> evidence,
            @JsonProperty("evidence_image") String evidenceImage) {
    }

    private record EvidenceDto(
            String title,
            String description,
            List<String> tags,
            @JsonProperty("start_sec") double startSec,
            @JsonProperty("end_sec") double endSec) {
    }
}
```

**참고:** 이미지/음성 클라이언트(`SpaiHttpDetectionClient`, `AntiDeepfakeHttpDetectionClient`)는 `RestClient` 빈이 하나뿐이라 `@RequiredArgsConstructor`로 충분했지만, 여기는 같은 타입 빈이 2개(`detectionRestClient`, `videoDetectionRestClient`)라 `@Qualifier`로 명시해야 한다 — 클래스에 `@RequiredArgsConstructor`가 없는 것(위 코드 확인)이 의도한 상태다.

- [ ] **Step 8: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.detection.dfdc.DfdcHttpDetectionClientTest"`
Expected: PASS (3개 전부)

- [ ] **Step 9: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (Spring 컨텍스트를 띄우는 테스트가 있다면 `videoDetectionRestClient` 빈도 정상 등록되는지 여기서 확인됨)

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/detection/VideoDetectionClient.java src/main/java/com/veritae/veritae_server/detection/dfdc/DfdcHttpDetectionClient.java src/test/java/com/veritae/veritae_server/detection/dfdc/DfdcHttpDetectionClientTest.java src/main/java/com/veritae/veritae_server/detection/DetectionProperties.java src/main/java/com/veritae/veritae_server/config/DetectionClientConfig.java src/main/resources/application.properties
git commit -m "feat(detection): 영상 탐지 서버(selimsef/dfdc) 호출 클라이언트 추가 (전용 긴 타임아웃)"
```

---

## Task 5: `InvalidVideoFileException` + 비동기 설정(`AsyncConfig`)

**Files:**
- Create: `src/main/java/com/veritae/veritae_server/analysis/InvalidVideoFileException.java`
- Create: `src/main/java/com/veritae/veritae_server/config/AsyncConfig.java`

**Interfaces:**
- Consumes: 없음
- Produces: `analysis.InvalidVideoFileException`, `@EnableAsync` 활성화 + `"videoAnalysisExecutor"` 이름의 `Executor` 빈 — Task 6이 사용한다.

- [ ] **Step 1: `InvalidVideoFileException` 작성**

`src/main/java/com/veritae/veritae_server/analysis/InvalidVideoFileException.java`:

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 업로드된 영상 파일이 비어있거나 지원하지 않는 형식/용량인 경우.
 */
public class InvalidVideoFileException extends DomainException {

    public InvalidVideoFileException(String message) {
        super("INVALID_VIDEO_FILE", HttpStatus.BAD_REQUEST, "Invalid Video File", message);
    }
}
```

- [ ] **Step 2: `AsyncConfig` 작성**

`src/main/java/com/veritae/veritae_server/config/AsyncConfig.java`:

```java
package com.veritae.veritae_server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 영상 분석 비동기 처리 전용 스레드풀. 코어/최대 크기를 작게 잡은 이유는 개인 프로젝트
 * 규모라 동시 영상 분석 요청이 많지 않을 것으로 예상되고, 데스크탑(3060Ti) GPU가 하나뿐이라
 * 어차피 순차 처리가 자연스럽기 때문 - 큐가 다 차면 5번째 요청부터는 큐잉되어 기다린다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "videoAnalysisExecutor")
    public Executor videoAnalysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("video-analysis-");
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 3: 빌드해서 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/analysis/InvalidVideoFileException.java src/main/java/com/veritae/veritae_server/config/AsyncConfig.java
git commit -m "feat(config): 영상 분석용 InvalidVideoFileException + 비동기 처리 스레드풀 설정 추가"
```

---

## Task 6: `VideoAnalysisService` + `VideoAnalysisAsyncWorker` + `AnalysisJobView`

**Files:**
- Create: `src/main/java/com/veritae/veritae_server/analysis/AnalysisJobView.java`
- Create: `src/main/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorker.java`
- Create: `src/main/java/com/veritae/veritae_server/analysis/VideoAnalysisService.java`
- Create: `src/test/java/com/veritae/veritae_server/analysis/VideoAnalysisServiceTest.java`
- Create: `src/test/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorkerTest.java`

**Interfaces:**
- Consumes: Task 3의 `AnalysisJob`/`AnalysisJobRepository`/`AnalysisJobStatus`/`AnalysisJobNotFoundException`, Task 4의 `VideoDetectionClient`, Task 5의 `InvalidVideoFileException`
- Produces: `analysis.VideoAnalysisService.submitVideo(MultipartFile file, UUID memberId): UUID`, `analysis.VideoAnalysisService.getJob(UUID jobId, UUID requesterId): AnalysisJobView`, `analysis.AnalysisJobView(UUID jobId, AnalysisJobStatus status, AiDetectionResult result, String errorMessage)` — Task 7이 사용한다.

**왜 처리 로직을 별도 `VideoAnalysisAsyncWorker` 빈으로 분리하는가 (중요 — 흔한 함정):** `@Async`는 스프링 프록시를 거쳐야 동작한다. 같은 클래스 안에서 `this.processAsync(...)`처럼 자기 자신을 호출하면 프록시를 안 거치므로 `@Async`가 조용히 무시되고 동기로 실행된다(스프링 AOP의 잘 알려진 self-invocation 함정). 그래서 비동기로 처리할 메서드를 `VideoAnalysisService`가 의존하는 별도 빈(`VideoAnalysisAsyncWorker`)으로 분리해, `submitVideo()`가 다른 빈의 메서드를 호출하는 형태(프록시를 거침)로 만든다.

- [ ] **Step 1: `AnalysisJobView` record 작성**

`src/main/java/com/veritae/veritae_server/analysis/AnalysisJobView.java`:

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobStatus;

import java.util.UUID;

/**
 * AnalysisJob 조회 결과. result 는 status가 COMPLETED일 때만 채워진다(resultJson을
 * VideoAnalysisService가 역직렬화해서 넣어준다 - AnalysisJob 엔티티 자체는 JSON
 * 역직렬화 책임을 모른다).
 */
public record AnalysisJobView(UUID jobId, AnalysisJobStatus status, AiDetectionResult result, String errorMessage) {
}
```

- [ ] **Step 2: 실패하는 테스트 작성 (`VideoAnalysisAsyncWorker`)**

`src/test/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorkerTest.java`:

```java
package com.veritae.veritae_server.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.VideoDetectionClient;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJob;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobRepository;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoAnalysisAsyncWorkerTest {

    @Mock
    private VideoDetectionClient videoDetectionClient;

    @Mock
    private AnalysisJobRepository analysisJobRepository;

    @Captor
    private ArgumentCaptor<AnalysisJob> jobCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private VideoAnalysisAsyncWorker worker;

    @Test
    void process_withSuccessfulDetection_shouldMarkJobCompletedWithResultJson() {
        // Given
        worker = new VideoAnalysisAsyncWorker(videoDetectionClient, analysisJobRepository, objectMapper);
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenReturn(new AiDetectionResult("dfdc", 0.91, List.of(), "base64png"));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        verify(analysisJobRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
        AnalysisJob savedJob = jobCaptor.getValue();
        assertThat(savedJob.getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(savedJob.getResultJson()).contains("\"model\":\"dfdc\"").contains("0.91");
    }

    @Test
    void process_whenDetectionThrows_shouldMarkJobFailedWithErrorMessage() {
        // Given
        worker = new VideoAnalysisAsyncWorker(videoDetectionClient, analysisJobRepository, objectMapper);
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(videoDetectionClient.detectVideo(any(), any(), any()))
                .thenThrow(new DetectionServiceException("탐지 서버 호출에 실패했습니다.", null));

        // When
        worker.process(job.getId(), "fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        verify(analysisJobRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
        AnalysisJob savedJob = jobCaptor.getValue();
        assertThat(savedJob.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(savedJob.getErrorMessage()).isEqualTo("탐지 서버 호출에 실패했습니다.");
    }
}
```

(`import static org.mockito.ArgumentMatchers.any;` 를 파일 상단에 추가할 것)

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.VideoAnalysisAsyncWorkerTest"`
Expected: FAIL — `VideoAnalysisAsyncWorker` 클래스가 없어 컴파일 에러

- [ ] **Step 4: `VideoAnalysisAsyncWorker` 구현**

`src/main/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorker.java`:

```java
package com.veritae.veritae_server.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.detection.VideoDetectionClient;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJob;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * VideoAnalysisService.submitVideo() 가 job을 만든 뒤 이 빈의 process()를 호출해
 * 실제 탐지 서버 호출 + 상태 갱신을 비동기로 수행한다. 별도 빈으로 분리한 이유는
 * VideoAnalysisService의 클래스 주석 참고(스프링 @Async self-invocation 함정).
 */
@Component
@RequiredArgsConstructor
public class VideoAnalysisAsyncWorker {

    private final VideoDetectionClient videoDetectionClient;
    private final AnalysisJobRepository analysisJobRepository;
    private final ObjectMapper objectMapper;

    @Async("videoAnalysisExecutor")
    public void process(UUID jobId, byte[] videoBytes, String filename, String contentType) {
        AnalysisJob job = analysisJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("방금 생성한 job을 찾을 수 없습니다: " + jobId));
        job.markProcessing();
        analysisJobRepository.save(job);

        try {
            AiDetectionResult result = videoDetectionClient.detectVideo(videoBytes, filename, contentType);
            job.markCompleted(writeResultJson(result));
        } catch (Exception e) {
            job.markFailed(e.getMessage());
        }
        analysisJobRepository.save(job);
    }

    private String writeResultJson(AiDetectionResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.VideoAnalysisAsyncWorkerTest"`
Expected: PASS (2개 전부)

- [ ] **Step 6: 실패하는 테스트 작성 (`VideoAnalysisService`)**

`src/test/java/com/veritae/veritae_server/analysis/VideoAnalysisServiceTest.java`:

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJob;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobRepository;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoAnalysisServiceTest {

    @Mock
    private AnalysisJobRepository analysisJobRepository;

    @Mock
    private VideoAnalysisAsyncWorker videoAnalysisAsyncWorker;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private VideoAnalysisService videoAnalysisService;

    @BeforeEach
    void setUp() {
        videoAnalysisService = new VideoAnalysisService(analysisJobRepository, videoAnalysisAsyncWorker, objectMapper);
    }

    @Test
    void submitVideo_withValidMp4_shouldCreatePendingJobAndTriggerAsyncWorker() throws Exception {
        // Given
        var file = new MockMultipartFile("file", "test.mp4", "video/mp4", "fake-bytes".getBytes());
        UUID memberId = UUID.randomUUID();

        // When
        UUID jobId = videoAnalysisService.submitVideo(file, memberId);

        // Then
        assertThat(jobId).isNotNull();
        verify(analysisJobRepository).save(any(AnalysisJob.class));
        verify(videoAnalysisAsyncWorker).process(org.mockito.ArgumentMatchers.eq(jobId), any(), org.mockito.ArgumentMatchers.eq("test.mp4"), org.mockito.ArgumentMatchers.eq("video/mp4"));
    }

    @Test
    void submitVideo_withEmptyFile_shouldThrowInvalidVideoFileException() {
        // Given
        var file = new MockMultipartFile("file", "test.mp4", "video/mp4", new byte[0]);

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.submitVideo(file, UUID.randomUUID()))
                .isInstanceOf(InvalidVideoFileException.class);
        verifyNoInteractions(analysisJobRepository, videoAnalysisAsyncWorker);
    }

    @Test
    void submitVideo_withUnsupportedContentType_shouldThrowInvalidVideoFileException() {
        // Given
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "not-a-video".getBytes());

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.submitVideo(file, UUID.randomUUID()))
                .isInstanceOf(InvalidVideoFileException.class);
        verifyNoInteractions(analysisJobRepository, videoAnalysisAsyncWorker);
    }

    @Test
    void submitVideo_withFileLargerThan100Mb_shouldThrowInvalidVideoFileException() {
        // Given
        byte[] tooLarge = new byte[101 * 1024 * 1024];
        var file = new MockMultipartFile("file", "test.mp4", "video/mp4", tooLarge);

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.submitVideo(file, UUID.randomUUID()))
                .isInstanceOf(InvalidVideoFileException.class);
        verifyNoInteractions(analysisJobRepository, videoAnalysisAsyncWorker);
    }

    @Test
    void getJob_withOwnCompletedJob_shouldReturnViewWithDeserializedResult() throws Exception {
        // Given
        UUID memberId = UUID.randomUUID();
        AnalysisJob job = AnalysisJob.submit(memberId);
        job.markCompleted(objectMapper.writeValueAsString(new AiDetectionResult("dfdc", 0.91, List.of(), null)));
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        // When
        AnalysisJobView view = videoAnalysisService.getJob(job.getId(), memberId);

        // Then
        assertThat(view.status()).isEqualTo(AnalysisJobStatus.COMPLETED);
        assertThat(view.result().model()).isEqualTo("dfdc");
        assertThat(view.result().score()).isEqualTo(0.91);
        assertThat(view.errorMessage()).isNull();
    }

    @Test
    void getJob_withPendingJob_shouldReturnViewWithNullResult() {
        // Given
        UUID memberId = UUID.randomUUID();
        AnalysisJob job = AnalysisJob.submit(memberId);
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        // When
        AnalysisJobView view = videoAnalysisService.getJob(job.getId(), memberId);

        // Then
        assertThat(view.status()).isEqualTo(AnalysisJobStatus.PENDING);
        assertThat(view.result()).isNull();
    }

    @Test
    void getJob_withNonExistentJob_shouldThrowAnalysisJobNotFoundException() {
        // Given
        UUID jobId = UUID.randomUUID();
        when(analysisJobRepository.findById(jobId)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> videoAnalysisService.getJob(jobId, UUID.randomUUID()))
                .isInstanceOf(AnalysisJobNotFoundException.class);
    }

    @Test
    void getJob_withAnotherMembersJob_shouldThrowAnalysisJobNotFoundException() {
        // Given
        AnalysisJob job = AnalysisJob.submit(UUID.randomUUID());
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        // When / Then: 요청자가 소유자가 아님
        assertThatThrownBy(() -> videoAnalysisService.getJob(job.getId(), UUID.randomUUID()))
                .isInstanceOf(AnalysisJobNotFoundException.class);
    }
}
```

- [ ] **Step 7: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.VideoAnalysisServiceTest"`
Expected: FAIL — `VideoAnalysisService` 클래스가 없어 컴파일 에러

- [ ] **Step 8: `VideoAnalysisService` 구현**

`src/main/java/com/veritae/veritae_server/analysis/VideoAnalysisService.java`:

```java
package com.veritae.veritae_server.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJob;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobRepository;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.UUID;

/**
 * 영상 검증 + job 생성 + (별도 빈 VideoAnalysisAsyncWorker를 통한) 비동기 처리 트리거 +
 * job 조회를 담당한다. 처리 자체를 이 클래스 안에 두지 않고 VideoAnalysisAsyncWorker로
 * 위임하는 이유는 그 클래스의 주석 참고(스프링 @Async self-invocation 함정).
 */
@Service
@RequiredArgsConstructor
public class VideoAnalysisService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("video/mp4", "video/quicktime", "video/x-msvideo");
    private static final long MAX_FILE_SIZE_BYTES = 100L * 1024 * 1024;

    private final AnalysisJobRepository analysisJobRepository;
    private final VideoAnalysisAsyncWorker videoAnalysisAsyncWorker;
    private final ObjectMapper objectMapper;

    public UUID submitVideo(MultipartFile file, UUID memberId) {
        validate(file);
        byte[] videoBytes;
        try {
            videoBytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("업로드된 파일을 읽을 수 없습니다.", e);
        }

        AnalysisJob job = AnalysisJob.submit(memberId);
        analysisJobRepository.save(job);

        videoAnalysisAsyncWorker.process(job.getId(), videoBytes, file.getOriginalFilename(), file.getContentType());
        return job.getId();
    }

    public AnalysisJobView getJob(UUID jobId, UUID requesterId) {
        AnalysisJob job = analysisJobRepository.findById(jobId)
                .orElseThrow(() -> new AnalysisJobNotFoundException(jobId));
        if (!job.getMemberId().equals(requesterId)) {
            throw new AnalysisJobNotFoundException(jobId);
        }

        AiDetectionResult result = job.getStatus() == AnalysisJobStatus.COMPLETED
                ? readResultJson(job.getResultJson())
                : null;
        return new AnalysisJobView(job.getId(), job.getStatus(), result, job.getErrorMessage());
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidVideoFileException("빈 파일은 분석할 수 없습니다.");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new InvalidVideoFileException("지원하지 않는 파일 형식입니다: " + file.getContentType());
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidVideoFileException("파일 용량이 100MB를 초과합니다.");
        }
    }

    private AiDetectionResult readResultJson(String json) {
        try {
            return objectMapper.readValue(json, AiDetectionResult.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 9: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.VideoAnalysisServiceTest"`
Expected: PASS (8개 전부)

- [ ] **Step 10: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 11: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/analysis/AnalysisJobView.java src/main/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorker.java src/main/java/com/veritae/veritae_server/analysis/VideoAnalysisService.java src/test/java/com/veritae/veritae_server/analysis/VideoAnalysisServiceTest.java src/test/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorkerTest.java
git commit -m "feat(analysis): VideoAnalysisService 추가 (파일 검증 + job 생성 + 비동기 처리 + 조회)"
```

---

## Task 7: `AnalysisApiMapper` 확장 + `analyzeVideo`/`getAnalysisJob` 배선 + 통합 테스트

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/api/AnalysisApiMapper.java`
- Modify: `src/main/java/com/veritae/veritae_server/api/AnalysisApiDelegateImpl.java`
- Modify: `src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java`

**Interfaces:**
- Consumes: Task 6의 `analysis.VideoAnalysisService`, Task 1의 생성된 `AnalysisApiDelegate.analyzeVideo`/`getAnalysisJob`, `AnalysisJobAcceptedResponse`, `AnalysisJobResponse`, `security.AuthenticatedMemberResolver`(기존)
- Produces: 없음 (최종 엔드포인트)

- [ ] **Step 1: `AnalysisApiMapper`에 job 응답 매핑 메서드 추가**

`src/main/java/com/veritae/veritae_server/api/AnalysisApiMapper.java` 전체를 아래로 교체:

```java
package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AnalysisJobView;
import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.openapi.model.AnalysisJobAcceptedResponse;
import com.veritae.veritae_server.openapi.model.AnalysisJobResponse;
import com.veritae.veritae_server.openapi.model.AudioAnalysisResponse;
import com.veritae.veritae_server.openapi.model.Evidence;
import com.veritae.veritae_server.openapi.model.ImageAnalysisResponse;

import java.util.List;
import java.util.UUID;

public final class AnalysisApiMapper {

    private AnalysisApiMapper() {
    }

    public static ImageAnalysisResponse toResponse(AiDetectionResult result) {
        return new ImageAnalysisResponse(toOpenApiResult(result));
    }

    public static AudioAnalysisResponse toAudioResponse(AiDetectionResult result) {
        return new AudioAnalysisResponse(toOpenApiResult(result));
    }

    public static AnalysisJobAcceptedResponse toJobAcceptedResponse(UUID jobId) {
        return new AnalysisJobAcceptedResponse(jobId);
    }

    public static AnalysisJobResponse toJobResponse(AnalysisJobView view) {
        var aiDetection = view.result() != null ? toOpenApiResult(view.result()) : null;
        return new AnalysisJobResponse(view.jobId(), view.status().name(), aiDetection, view.errorMessage());
    }

    private static com.veritae.veritae_server.openapi.model.AiDetectionResult toOpenApiResult(AiDetectionResult result) {
        List<Evidence> evidence = result.evidence().stream()
                .map(e -> new Evidence(e.title(), e.description(), e.tags(), e.startSec(), e.endSec()))
                .toList();
        return new com.veritae.veritae_server.openapi.model.AiDetectionResult(
                result.model(), result.score(), evidence, result.evidenceImage());
    }
}
```

**참고**: `AnalysisJobResponse`의 생성자 인자 순서(`jobId, status, aiDetection, errorMessage`)는 Task 1의 openapi.yaml에 선언한 `required`/`properties` 순서(`jobId, status` 다음 `aiDetection, errorMessage`)를 따른다. 만약 `./gradlew compileJava` 결과 생성자 인자 순서가 다르면(openapi-generator가 required 필드를 먼저, nullable 필드를 나중에 배치하는 경우가 있음), 생성된 `AnalysisJobResponse.java`를 확인해서 인자 순서를 맞출 것.

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java`의 클래스 상단 필드 목록(`audioAnalysisService` 다음)에 추가:

```java
    @MockitoBean
    private com.veritae.veritae_server.analysis.VideoAnalysisService videoAnalysisService;

    @MockitoBean
    private com.veritae.veritae_server.security.AuthenticatedMemberResolver authenticatedMemberResolver;
```

그리고 파일 맨 아래(마지막 `}` 직전)에 테스트 메서드로 추가:

```java
    @Test
    @WithMockUser
    void analyzeVideo_withAuthenticatedMemberAndValidFile_shouldReturn202WithJobId() throws Exception {
        var file = new MockMultipartFile("file", "test.mp4", "video/mp4", "fake-bytes".getBytes());
        java.util.UUID jobId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(videoAnalysisService.submitVideo(any(), org.mockito.ArgumentMatchers.eq(memberId))).thenReturn(jobId);

        mockMvc.perform(multipart("/api/v1/analysis/video").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void analyzeVideo_withoutAuthentication_shouldReturn401() throws Exception {
        var file = new MockMultipartFile("file", "test.mp4", "video/mp4", "fake-bytes".getBytes());

        mockMvc.perform(multipart("/api/v1/analysis/video").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void getAnalysisJob_withCompletedJob_shouldReturn200WithResult() throws Exception {
        java.util.UUID jobId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(videoAnalysisService.getJob(jobId, memberId)).thenReturn(
                new com.veritae.veritae_server.analysis.AnalysisJobView(
                        jobId,
                        com.veritae.veritae_server.domain.analysisjob.AnalysisJobStatus.COMPLETED,
                        new AiDetectionResult("dfdc", 0.91,
                                List.of(new Evidence("얼굴 조작 의심 구간", "3.0초~7.0초 구간에서 얼굴 합성 흔적이 감지됨",
                                        List.of("temporal", "face-swap"), 3.0, 7.0)),
                                "base64pngdata"),
                        null));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/analysis/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.aiDetection.model").value("dfdc"))
                .andExpect(jsonPath("$.aiDetection.evidenceImage").value("base64pngdata"))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    @Test
    @WithMockUser
    void getAnalysisJob_withNonExistentJob_shouldReturn404() throws Exception {
        java.util.UUID jobId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(videoAnalysisService.getJob(jobId, memberId))
                .thenThrow(new com.veritae.veritae_server.analysis.AnalysisJobNotFoundException(jobId));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/analysis/jobs/" + jobId))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAnalysisJob_withoutAuthentication_shouldReturn401() throws Exception {
        java.util.UUID jobId = java.util.UUID.randomUUID();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/analysis/jobs/" + jobId))
                .andExpect(status().isUnauthorized());
    }
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.api.AnalysisApiControllerTest"`
Expected: FAIL — `analyzeVideo_...`/`getAnalysisJob_...` 테스트가 501(Not Implemented)로 실패 (delegate가 아직 미구현)

- [ ] **Step 4: `AnalysisApiDelegateImpl`에 `analyzeVideo`/`getAnalysisJob` 구현 추가**

`src/main/java/com/veritae/veritae_server/api/AnalysisApiDelegateImpl.java` 전체를 아래로 교체:

```java
package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AudioAnalysisService;
import com.veritae.veritae_server.analysis.ImageAnalysisService;
import com.veritae.veritae_server.analysis.VideoAnalysisService;
import com.veritae.veritae_server.openapi.api.AnalysisApiDelegate;
import com.veritae.veritae_server.openapi.model.AnalysisJobAcceptedResponse;
import com.veritae.veritae_server.openapi.model.AnalysisJobResponse;
import com.veritae.veritae_server.openapi.model.AudioAnalysisResponse;
import com.veritae.veritae_server.openapi.model.ImageAnalysisResponse;
import com.veritae.veritae_server.security.AuthenticatedMemberResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalysisApiDelegateImpl implements AnalysisApiDelegate {

    private final ImageAnalysisService imageAnalysisService;
    private final AudioAnalysisService audioAnalysisService;
    private final VideoAnalysisService videoAnalysisService;
    private final AuthenticatedMemberResolver authenticatedMemberResolver;

    @Override
    public ResponseEntity<ImageAnalysisResponse> analyzeImage(MultipartFile file) {
        var result = imageAnalysisService.analyzeImage(file);
        return ResponseEntity.ok(AnalysisApiMapper.toResponse(result));
    }

    @Override
    public ResponseEntity<AudioAnalysisResponse> analyzeAudio(MultipartFile file) {
        var result = audioAnalysisService.analyzeAudio(file);
        return ResponseEntity.ok(AnalysisApiMapper.toAudioResponse(result));
    }

    @Override
    public ResponseEntity<AnalysisJobAcceptedResponse> analyzeVideo(MultipartFile file) {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        UUID jobId = videoAnalysisService.submitVideo(file, memberId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AnalysisApiMapper.toJobAcceptedResponse(jobId));
    }

    @Override
    public ResponseEntity<AnalysisJobResponse> getAnalysisJob(UUID jobId) {
        UUID memberId = authenticatedMemberResolver.currentMemberId();
        var view = videoAnalysisService.getJob(jobId, memberId);
        return ResponseEntity.ok(AnalysisApiMapper.toJobResponse(view));
    }
}
```

**참고**: `getAnalysisJob`의 파라미터 타입이 생성된 `AnalysisApiDelegate` 인터페이스와 다르면(예: `String` 대신 `UUID`가 아니면) 컴파일 에러로 바로 드러난다 — Task 1 Step 5에서 생성된 실제 인터페이스 시그니처를 확인할 것(이 레포는 `format: uuid` 경로 파라미터를 `UUID`로 생성하는 것으로 이미 확인됨 - `MemberResponse.id` 필드가 `UUID`로 생성된 전례 참고).

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.api.AnalysisApiControllerTest"`
Expected: PASS (전체)

- [ ] **Step 6: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/api/AnalysisApiMapper.java src/main/java/com/veritae/veritae_server/api/AnalysisApiDelegateImpl.java src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java
git commit -m "feat(analysis): 영상 AI 판독 API 추가 (POST /api/v1/analysis/video, GET /api/v1/analysis/jobs/{jobId})"
```

이 태스크까지 완료되면 Spring 쪽은 끝난다. Task 8부터는 `veritae-detection-server`(Python) 작업이다.

---

## Task 8: Python — config 확장 + `dfdc_runner.py` (subprocess 래퍼)

**Files:**
- Modify: `C:\Users\sb112\IdeaProjects\veritae-detection-server\app\config.py`
- Create: `C:\Users\sb112\IdeaProjects\veritae-detection-server\app\services\dfdc_runner.py`
- Create: `C:\Users\sb112\IdeaProjects\veritae-detection-server\tests\test_dfdc_runner.py`

**Interfaces:**
- Consumes: 없음 (subprocess로 Task 10의 스크립트를 실행하지만, 실행 자체는 mocking되어 테스트에선 스크립트 존재 여부와 무관)
- Produces: `app.services.dfdc_runner.run_dfdc_inference(video_bytes: bytes, filename: str) -> DfdcResult`, `DfdcResult(score: float, evidence: list[dict], evidence_image: str | None)`, `DfdcInferenceError` — Task 9가 사용한다.

**주의**: SPAI/AntiDeepfake 때와 동일하게, 실제 GPU/체크포인트 없이도 이 태스크는 전부 자동 테스트 가능하다(subprocess 자체를 mock). 실제 모델이 맞물려 돌아가는지는 Task 10 + 사용자의 데스크탑 실행에서 검증한다.

- [ ] **Step 1: `config.py`에 dfdc 설정 추가**

`C:\Users\sb112\IdeaProjects\veritae-detection-server\app\config.py`의 `Settings.__init__` 메서드 맨 끝(`self.antideepfake_work_dir.mkdir(...)` 다음 줄)에 추가:

```python

        # --- selimsef/dfdc_deepfake_challenge(영상, 얼굴조작 딥페이크) 설정. SPAI/AntiDeepfake와
        # 마찬가지로 무거운 의존성(opencv-python, facenet-pytorch 등)은 별도 conda env(dfdc)에
        # 격리하고, 여기(detection-api env)는 subprocess로만 부른다.
        dfdc_repo_dir = os.environ.get("DFDC_REPO_DIR")
        if not dfdc_repo_dir:
            raise RuntimeError(
                "DFDC_REPO_DIR environment variable is required "
                "(absolute path to the cloned selimsef/dfdc_deepfake_challenge repo)"
            )
        self.dfdc_repo_dir = Path(dfdc_repo_dir)
        self.dfdc_python = os.environ.get("DFDC_PYTHON", "python")
        self.dfdc_script = Path(
            os.environ.get(
                "DFDC_SCRIPT",
                str(Path(__file__).resolve().parent.parent / "scripts" / "dfdc_infer.py"),
            )
        )
        # 저장소가 배포하는 7개 체크포인트 앙상블 중 하나만 쓴다(§3, 8GB GPU 고려) - 필요하면
        # env var로 다른 체크포인트로 바꿀 수 있다.
        self.dfdc_checkpoint = Path(
            os.environ.get(
                "DFDC_CHECKPOINT",
                "./weights/final_555_DeepFakeClassifier_tf_efficientnet_b7_ns_0_19",
            )
        )
        # 얼굴검출+CNN추론이 여러 프레임에 걸쳐 겹쳐 오디오(300s)보다 오래 걸릴 걸로 예상되나
        # 실측 데이터 없음(2026-08-26 기준) - 넉넉하게 잡고 실측 후 조정.
        self.dfdc_timeout_seconds = int(os.environ.get("DFDC_TIMEOUT_SECONDS", "600"))
        self.dfdc_work_dir = Path(os.environ.get("DFDC_WORK_DIR", "./tmp")).resolve()
        self.dfdc_work_dir.mkdir(parents=True, exist_ok=True)
```

- [ ] **Step 2: 실패하는 테스트 작성**

`C:\Users\sb112\IdeaProjects\veritae-detection-server\tests\test_dfdc_runner.py`:

```python
import json
import subprocess
from unittest.mock import MagicMock, patch

import pytest

from app.services.dfdc_runner import DfdcInferenceError, _parse_result, _safe_filename


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("video.mp4", "video.mp4"),
        ("../../etc/passwd", "passwd"),
        ("..\\..\\Windows\\System32\\evil.dll", "evil.dll"),
        ("/etc/passwd", "passwd"),
        ("C:\\Windows\\System32\\evil.dll", "evil.dll"),
        ("..", "upload"),
        ("", "upload"),
    ],
)
def test_safe_filename_strips_path_traversal(raw, expected):
    assert _safe_filename(raw) == expected


def test_parse_result_with_valid_json(tmp_path):
    output_file = tmp_path / "result.json"
    output_file.write_text(
        json.dumps({"score": 0.91, "evidence": [{"title": "x"}], "evidence_image": "base64data"}),
        encoding="utf-8",
    )

    result = _parse_result(output_file)

    assert result.score == 0.91
    assert result.evidence == [{"title": "x"}]
    assert result.evidence_image == "base64data"


def test_parse_result_with_missing_evidence_image_defaults_to_none(tmp_path):
    output_file = tmp_path / "result.json"
    output_file.write_text(json.dumps({"score": 0.1, "evidence": []}), encoding="utf-8")

    result = _parse_result(output_file)

    assert result.evidence_image is None


def test_parse_result_missing_score_raises(tmp_path):
    output_file = tmp_path / "result.json"
    output_file.write_text(json.dumps({"evidence": []}), encoding="utf-8")

    with pytest.raises(DfdcInferenceError, match="missing 'score'"):
        _parse_result(output_file)


def test_parse_result_invalid_json_raises(tmp_path):
    output_file = tmp_path / "result.json"
    output_file.write_text("not json", encoding="utf-8")

    with pytest.raises(DfdcInferenceError):
        _parse_result(output_file)


@patch("app.services.dfdc_runner.subprocess.run")
@patch("app.services.dfdc_runner.get_settings")
def test_run_dfdc_inference_success(mock_get_settings, mock_run, tmp_path):
    from app.services.dfdc_runner import run_dfdc_inference

    settings = MagicMock()
    settings.dfdc_work_dir = tmp_path
    settings.dfdc_python = "python"
    settings.dfdc_script = tmp_path / "dfdc_infer.py"
    settings.dfdc_repo_dir = tmp_path
    settings.dfdc_checkpoint = tmp_path / "checkpoint"
    settings.dfdc_timeout_seconds = 600
    mock_get_settings.return_value = settings

    def fake_run(command, **kwargs):
        output_path = command[command.index("--output") + 1]
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump({"score": 0.91, "evidence": [], "evidence_image": None}, f)
        return MagicMock(returncode=0, stderr="")

    mock_run.side_effect = fake_run

    result = run_dfdc_inference(b"fake-video-bytes", "test.mp4")

    assert result.score == 0.91


@patch("app.services.dfdc_runner.subprocess.run")
@patch("app.services.dfdc_runner.get_settings")
def test_run_dfdc_inference_nonzero_exit_raises(mock_get_settings, mock_run, tmp_path):
    from app.services.dfdc_runner import run_dfdc_inference

    settings = MagicMock()
    settings.dfdc_work_dir = tmp_path
    settings.dfdc_python = "python"
    settings.dfdc_script = tmp_path / "dfdc_infer.py"
    settings.dfdc_repo_dir = tmp_path
    settings.dfdc_checkpoint = tmp_path / "checkpoint"
    settings.dfdc_timeout_seconds = 600
    mock_get_settings.return_value = settings
    mock_run.return_value = MagicMock(returncode=1, stderr="CUDA out of memory")

    with pytest.raises(DfdcInferenceError, match="CUDA out of memory"):
        run_dfdc_inference(b"fake-video-bytes", "test.mp4")


@patch("app.services.dfdc_runner.subprocess.run")
@patch("app.services.dfdc_runner.get_settings")
def test_run_dfdc_inference_timeout_raises(mock_get_settings, mock_run, tmp_path):
    from app.services.dfdc_runner import run_dfdc_inference

    settings = MagicMock()
    settings.dfdc_work_dir = tmp_path
    settings.dfdc_python = "python"
    settings.dfdc_script = tmp_path / "dfdc_infer.py"
    settings.dfdc_repo_dir = tmp_path
    settings.dfdc_checkpoint = tmp_path / "checkpoint"
    settings.dfdc_timeout_seconds = 600
    mock_get_settings.return_value = settings
    mock_run.side_effect = subprocess.TimeoutExpired(cmd="dfdc_infer.py", timeout=600)

    with pytest.raises(DfdcInferenceError, match="timed out"):
        run_dfdc_inference(b"fake-video-bytes", "test.mp4")
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `pytest tests/test_dfdc_runner.py -v`
Expected: FAIL — `app.services.dfdc_runner` 모듈이 없어 ImportError

- [ ] **Step 4: `dfdc_runner.py` 작성**

`C:\Users\sb112\IdeaProjects\veritae-detection-server\app\services\dfdc_runner.py`:

```python
import json
import shutil
import subprocess
import uuid
from pathlib import Path, PureWindowsPath

from app.config import get_settings


class DfdcInferenceError(RuntimeError):
    pass


class DfdcResult:
    def __init__(self, score: float, evidence: list[dict], evidence_image: str | None):
        self.score = score
        self.evidence = evidence
        self.evidence_image = evidence_image


def _safe_filename(filename: str) -> str:
    # PureWindowsPath treats both / and \ as separators, so this strips any
    # directory components regardless of host OS, preventing a crafted upload
    # filename from writing outside the job's temp dir. (spai_runner.py/antideepfake_runner.py와 동일 로직)
    name = PureWindowsPath(filename).name
    return name if name and name not in (".", "..") else "upload"


def run_dfdc_inference(video_bytes: bytes, filename: str) -> DfdcResult:
    settings = get_settings()
    job_dir = settings.dfdc_work_dir / uuid.uuid4().hex
    job_dir.mkdir(parents=True, exist_ok=True)
    input_file = job_dir / _safe_filename(filename)
    output_file = job_dir / "result.json"
    input_file.write_bytes(video_bytes)

    command = [
        settings.dfdc_python,
        str(settings.dfdc_script),
        "--repo-dir", str(settings.dfdc_repo_dir),
        "--checkpoint", str(settings.dfdc_checkpoint),
        "--input", str(input_file),
        "--output", str(output_file),
    ]

    try:
        result = subprocess.run(
            command,
            cwd=settings.dfdc_repo_dir,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=settings.dfdc_timeout_seconds,
        )

        if result.returncode != 0:
            raise DfdcInferenceError(f"DFDC inference failed: {result.stderr[-2000:]}")

        if not output_file.exists():
            raise DfdcInferenceError(f"expected output JSON not found: {output_file}")

        return _parse_result(output_file)
    except subprocess.TimeoutExpired as e:
        raise DfdcInferenceError(
            f"DFDC inference timed out after {settings.dfdc_timeout_seconds}s"
        ) from e
    finally:
        shutil.rmtree(job_dir, ignore_errors=True)


def _parse_result(output_file: Path) -> DfdcResult:
    try:
        data = json.loads(output_file.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError, OSError) as e:
        raise DfdcInferenceError(f"DFDC output JSON을 읽거나 파싱할 수 없습니다: {output_file}") from e
    if "score" not in data:
        raise DfdcInferenceError(f"DFDC output JSON missing 'score' field: {output_file}")
    return DfdcResult(
        score=data["score"],
        evidence=data.get("evidence", []),
        evidence_image=data.get("evidence_image"),
    )
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `pytest tests/test_dfdc_runner.py -v`
Expected: PASS (9개 전부)

- [ ] **Step 6: 전체 테스트 스위트 실행**

Run: `pytest`
Expected: 전체 PASS (기존 이미지/음성 테스트 포함)

- [ ] **Step 7: 커밋**

```bash
git add app/config.py app/services/dfdc_runner.py tests/test_dfdc_runner.py
git commit -m "feat(detection): 영상(selimsef/dfdc) subprocess 래퍼 추가"
```

---

## Task 9: Python — schemas 확장 + `/process/video` 라우터

**Files:**
- Modify: `C:\Users\sb112\IdeaProjects\veritae-detection-server\app\schemas.py`
- Create: `C:\Users\sb112\IdeaProjects\veritae-detection-server\app\routers\video.py`
- Modify: `C:\Users\sb112\IdeaProjects\veritae-detection-server\app\main.py`
- Create: `C:\Users\sb112\IdeaProjects\veritae-detection-server\tests\test_video_router.py`

**Interfaces:**
- Consumes: Task 8의 `app.services.dfdc_runner.run_dfdc_inference(video_bytes, filename) -> DfdcResult`, `DfdcInferenceError`
- Produces: `POST /process/video` 엔드포인트 — Spring의 `DfdcHttpDetectionClient`(Task 4)가 호출하는 대상.

- [ ] **Step 1: 실패하는 테스트 작성**

`C:\Users\sb112\IdeaProjects\veritae-detection-server\tests\test_video_router.py`:

```python
import io

from fastapi.testclient import TestClient

from app.main import app
from app.routers import video as video_router
from app.services.dfdc_runner import DfdcInferenceError, DfdcResult

client = TestClient(app)


def test_process_video_returns_score_evidence_and_evidence_image(monkeypatch):
    monkeypatch.setattr(
        video_router,
        "run_dfdc_inference",
        lambda data, filename: DfdcResult(
            score=0.91,
            evidence=[
                {
                    "title": "얼굴 조작 의심 구간",
                    "description": "3.0초~7.0초 구간에서 얼굴 합성 흔적이 감지됨",
                    "tags": ["temporal", "face-swap"],
                    "start_sec": 3.0,
                    "end_sec": 7.0,
                }
            ],
            evidence_image="base64pngdata",
        ),
    )

    response = client.post(
        "/process/video",
        files={"file": ("test.mp4", io.BytesIO(b"fake-video-bytes"), "video/mp4")},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ai_detection"]["model"] == "dfdc"
    assert body["ai_detection"]["score"] == 0.91
    assert body["ai_detection"]["evidence_image"] == "base64pngdata"
    assert body["ai_detection"]["evidence"][0]["title"] == "얼굴 조작 의심 구간"


def test_process_video_with_null_evidence_image(monkeypatch):
    monkeypatch.setattr(
        video_router,
        "run_dfdc_inference",
        lambda data, filename: DfdcResult(score=0.1, evidence=[], evidence_image=None),
    )

    response = client.post(
        "/process/video",
        files={"file": ("test.mp4", io.BytesIO(b"fake-video-bytes"), "video/mp4")},
    )

    assert response.status_code == 200
    assert response.json()["ai_detection"]["evidence_image"] is None


def test_process_video_rejects_unsupported_content_type():
    response = client.post(
        "/process/video",
        files={"file": ("test.txt", io.BytesIO(b"not a video"), "text/plain")},
    )

    assert response.status_code == 415


def test_process_video_rejects_empty_file():
    response = client.post(
        "/process/video",
        files={"file": ("test.mp4", io.BytesIO(b""), "video/mp4")},
    )

    assert response.status_code == 400


def test_process_video_returns_502_on_inference_failure(monkeypatch):
    def raise_error(data, filename):
        raise DfdcInferenceError("boom")

    monkeypatch.setattr(video_router, "run_dfdc_inference", raise_error)

    response = client.post(
        "/process/video",
        files={"file": ("test.mp4", io.BytesIO(b"fake-video-bytes"), "video/mp4")},
    )

    assert response.status_code == 502
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `pytest tests/test_video_router.py -v`
Expected: FAIL — `app.routers.video` 모듈이 없어 ImportError

- [ ] **Step 3: `schemas.py`에 영상 응답 스키마 추가**

`C:\Users\sb112\IdeaProjects\veritae-detection-server\app\schemas.py`의 파일 맨 끝에 추가:

```python


class VideoDetectionResult(BaseModel):
    model: str
    score: float
    evidence: list[Evidence]
    evidence_image: str | None = None


class VideoAnalysisResponse(BaseModel):
    ai_detection: VideoDetectionResult
```

- [ ] **Step 4: `app/routers/video.py` 작성**

```python
from fastapi import APIRouter, File, HTTPException, UploadFile

from app.schemas import Evidence, VideoAnalysisResponse, VideoDetectionResult
from app.services.dfdc_runner import DfdcInferenceError, run_dfdc_inference

router = APIRouter()

ALLOWED_CONTENT_TYPES = {"video/mp4", "video/quicktime", "video/x-msvideo"}


@router.post("/process/video", response_model=VideoAnalysisResponse)
async def process_video(file: UploadFile = File(...)) -> VideoAnalysisResponse:
    if file.content_type not in ALLOWED_CONTENT_TYPES:
        raise HTTPException(
            status_code=415, detail=f"unsupported content type: {file.content_type}"
        )

    video_bytes = await file.read()
    if not video_bytes:
        raise HTTPException(status_code=400, detail="empty file")

    try:
        result = run_dfdc_inference(video_bytes, file.filename or "input.mp4")
    except DfdcInferenceError as e:
        raise HTTPException(status_code=502, detail=str(e)) from e

    evidence = [
        Evidence(
            title=e["title"],
            description=e["description"],
            tags=e["tags"],
            start_sec=e["start_sec"],
            end_sec=e["end_sec"],
        )
        for e in result.evidence
    ]
    return VideoAnalysisResponse(
        ai_detection=VideoDetectionResult(
            model="dfdc", score=result.score, evidence=evidence, evidence_image=result.evidence_image
        )
    )
```

- [ ] **Step 5: `app/main.py`에 라우터 등록**

`C:\Users\sb112\IdeaProjects\veritae-detection-server\app\main.py` 전체를 아래로 교체:

```python
from fastapi import FastAPI

from app.routers import audio, image, video

app = FastAPI(title="Veritae Detection Server")

app.include_router(image.router)
app.include_router(audio.router)
app.include_router(video.router)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
```

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `pytest tests/test_video_router.py -v`
Expected: PASS (5개 전부)

- [ ] **Step 7: 전체 테스트 스위트 실행**

Run: `pytest`
Expected: 전체 PASS (기존 이미지/음성 테스트 포함)

- [ ] **Step 8: 커밋**

```bash
git add app/schemas.py app/routers/video.py app/main.py tests/test_video_router.py
git commit -m "feat: 영상 분석 라우터 추가 (POST /process/video)"
```

---

## Task 10: Python — `scripts/dfdc_infer.py` (커스텀 추론 스크립트)

**Files:**
- Create: `C:\Users\sb112\IdeaProjects\veritae-detection-server\scripts\dfdc_infer.py`

**Interfaces:**
- Consumes: 없음 (독립 스크립트, subprocess로만 호출됨)
- Produces: `--output` 경로에 `{"score": float, "evidence": [...], "evidence_image": str|null}` JSON 파일 — Task 8의 `dfdc_runner.py`가 읽는 계약.

**⚠️ 이 태스크는 GPU/모델/opencv/facenet-pytorch 없이는 이 세션에서 실행도 테스트도 할 수 없다.** SPAI(`spai_runner`)/AntiDeepfake(`antideepfake_infer.py`) 때와 동일한 상황 — 로직은 selimsef 저장소의 실제 소스(`kernel_utils.py`, `training/zoo/classifiers.py`)를 직접 읽고 그대로 재사용하는 방식으로 작성했지만, 실제 체크포인트로 돌려보기 전까지는 정확성을 보장할 수 없다. 특히 Grad-CAM(히트맵) 부분은 §4에서 이미 "적용 가능 여부 데스크탑 검증 필요"로 명시된 부분이라 실패해도 조용히 `evidence_image=None`으로 넘어가게 만들었다. TDD 사이클(RED→GREEN) 없이 코드만 작성하고 커밋한다 — 검증은 사용자가 데스크탑에서 진행한다.

- [ ] **Step 1: `dfdc_infer.py` 작성**

`C:\Users\sb112\IdeaProjects\veritae-detection-server\scripts\dfdc_infer.py`:

```python
#!/usr/bin/env python
"""dfdc_infer.py

selimsef/dfdc_deepfake_challenge(단일 체크포인트, tf_efficientnet_b7_ns)로 영상 파일 하나를
추론해서, 전체 score와 시간 구간별(frame-level) evidence, (best-effort) 얼굴 히트맵
이미지를 JSON으로 저장한다.

`dfdc` conda env(opencv-python, facenet-pytorch, pytorch-grad-cam 등 설치됨)에서
실행되어야 한다. veritae-detection-server(FastAPI, detection-api env)는 이 스크립트를
subprocess로 호출하고 --output 경로의 JSON만 읽는다 - SPAI/AntiDeepfake 연동과 동일한 패턴.

selimsef 저장소는 프레임 추출에 ffmpeg가 아니라 OpenCV(cv2.VideoCapture)를 쓴다
(kernel_utils.VideoReader) - 얼굴 검출은 facenet-pytorch의 MTCNN
(kernel_utils.FaceExtractor). 이 스크립트는 그 두 클래스를 그대로 재사용한다.

원본 predict_on_video()(kernel_utils.py)는 프레임별 점수를 평균 내서(np.mean) 값 하나만
반환하고 프레임별 점수는 버린다 - 시간 구간 evidence를 만들려면 평균 내기 전 값이 필요해서
그 로직을 그대로 못 쓰고 이 파일에서 재구현했다
(docs(veritae-server 레포): docs/superpowers/specs/2026-08-26-video-ai-detection-design.md §3, §4).

⚠️ Grad-CAM(공간적 근거) 부분은 아직 데스크탑에서 검증 안 됨 - selimsef가 순수 CNN
(EfficientNet)이라 적용 가능성은 높다고 판단했지만 실제로 말이 되는 히트맵이 나오는지는
미확인이다. best-effort로 시도하고 실패하면 evidence_image를 null로 둔다(설계 §4 fallback).
"""
import argparse
import base64
import json
import re
import sys
from pathlib import Path

import cv2
import numpy as np
import torch

EVIDENCE_SCORE_THRESHOLD = 0.5
MAX_EVIDENCE_COUNT = 3
FRAMES_PER_VIDEO = 32
INPUT_SIZE = 380
DEFAULT_FPS = 30.0  # cv2가 fps를 못 읽을 때의 추정치 - 실측 필요


def _safe_filename(filename: str) -> str:
    from pathlib import PureWindowsPath

    name = PureWindowsPath(filename).name
    return name if name and name not in (".", "..") else "upload"


def load_model(repo_dir: Path, checkpoint_path: Path):
    sys.path.insert(0, str(repo_dir))
    from training.zoo.classifiers import DeepFakeClassifier  # selimsef 저장소 코드

    model = DeepFakeClassifier(encoder="tf_efficientnet_b7_ns").to("cuda")
    checkpoint = torch.load(str(checkpoint_path), map_location="cpu")
    state_dict = checkpoint.get("state_dict", checkpoint)
    model.load_state_dict({re.sub("^module.", "", k): v for k, v in state_dict.items()}, strict=True)
    model.eval()
    return model.half()


def get_fps(video_path: Path) -> float:
    cap = cv2.VideoCapture(str(video_path))
    fps = cap.get(cv2.CAP_PROP_FPS)
    cap.release()
    return fps if fps and fps > 0 else DEFAULT_FPS


def run_inference(model, repo_dir: Path, video_path: Path) -> dict:
    """selimsef 저장소의 VideoReader/FaceExtractor를 그대로 재사용해 얼굴 프레임을 뽑고,
    predict_on_video()가 버리는 프레임별 점수(평균 내기 전 값)를 직접 계산한다."""
    sys.path.insert(0, str(repo_dir))
    from kernel_utils import FaceExtractor, VideoReader, isotropically_resize_image, normalize_transform, put_to_center

    video_reader = VideoReader()
    video_read_fn = lambda x: video_reader.read_frames(x, num_frames=FRAMES_PER_VIDEO)
    face_extractor = FaceExtractor(video_read_fn)

    frame_data_list = face_extractor.process_video(str(video_path))

    faces_by_frame_idx = []
    for frame_data in frame_data_list:
        for face in frame_data["faces"]:
            faces_by_frame_idx.append((frame_data["frame_idx"], face))

    if not faces_by_frame_idx:
        raise RuntimeError("얼굴을 찾을 수 없습니다.")

    fps = get_fps(video_path)
    frame_idxs = [idx for idx, _ in faces_by_frame_idx]

    x = np.zeros((len(faces_by_frame_idx), INPUT_SIZE, INPUT_SIZE, 3), dtype=np.uint8)
    for i, (_, face) in enumerate(faces_by_frame_idx):
        resized_face = isotropically_resize_image(face, INPUT_SIZE)
        x[i] = put_to_center(resized_face, INPUT_SIZE)

    x_tensor = torch.tensor(x, device="cuda").float()
    x_tensor = x_tensor.permute((0, 3, 1, 2))
    for i in range(len(x_tensor)):
        x_tensor[i] = normalize_transform(x_tensor[i] / 255.0)

    with torch.no_grad():
        y_pred = model(x_tensor.half())
        frame_scores = torch.sigmoid(y_pred.squeeze()).float().cpu().numpy()
    if frame_scores.ndim == 0:
        frame_scores = np.array([frame_scores.item()])

    overall_score = float(np.mean(frame_scores))
    worst_idx = int(np.argmax(frame_scores))

    return {
        "overall_score": overall_score,
        "frame_idxs": frame_idxs,
        "frame_scores": frame_scores.tolist(),
        "fps": fps,
        "worst_frame_bgr": faces_by_frame_idx[worst_idx][1],
        "worst_frame_tensor": x_tensor[worst_idx],
        "model_ref": model,
    }


def build_evidence(frame_idxs: list[int], frame_scores: list[float], fps: float) -> list[dict]:
    """프레임별 점수에서 임계값(0.5) 이상인 연속 구간을 병합해 근거 카드로 변환한다.
    구간을 점수 최댓값 기준 정렬해 상위 3개만 남긴다 (antideepfake_infer.py의
    build_evidence와 동일한 로직 - frame_idx -> 초 변환만 fps 기반으로 다름, AntiDeepfake는
    고정 프레임 간격(20ms)을 쓰지만 영상은 프레임 인덱스가 균등 샘플링돼 fps가 필요함)."""
    segments: list[tuple[int, int, float]] = []
    start = None
    peak = 0.0
    for i, score in enumerate(frame_scores):
        if score >= EVIDENCE_SCORE_THRESHOLD:
            if start is None:
                start = i
                peak = score
            else:
                peak = max(peak, score)
        elif start is not None:
            segments.append((start, i - 1, peak))
            start = None
    if start is not None:
        segments.append((start, len(frame_scores) - 1, peak))

    segments.sort(key=lambda s: s[2], reverse=True)
    top_segments = segments[:MAX_EVIDENCE_COUNT]

    evidence = []
    for start_i, end_i, _peak in top_segments:
        start_sec = round(frame_idxs[start_i] / fps, 2)
        end_sec = round(frame_idxs[end_i] / fps, 2)
        evidence.append(
            {
                "title": "얼굴 조작 의심 구간",
                "description": f"{start_sec:.1f}초~{end_sec:.1f}초 구간에서 얼굴 합성 흔적이 감지됨",
                "tags": ["temporal", "face-swap"],
                "start_sec": start_sec,
                "end_sec": end_sec,
            }
        )
    return evidence


def try_generate_heatmap(model, face_tensor, face_bgr) -> str | None:
    """가장 의심스러운 프레임에 Grad-CAM을 적용해 base64 PNG를 반환한다. 실패하면 조용히
    None을 반환한다(전체 분석 실패로 이어지면 안 되므로 여기서 예외를 삼킨다) - 설계 §4 fallback.
    target_layer(model.encoder.conv_head)는 timm의 EfficientNet 구조를 근거로 추정한 것으로,
    실제 클래스 속성명이 다르면 데스크탑에서 실제 model 객체를 찍어보고 수정해야 한다."""
    try:
        from pytorch_grad_cam import GradCAM
        from pytorch_grad_cam.utils.image import show_cam_on_image

        target_layer = model.encoder.conv_head
        cam = GradCAM(model=model, target_layers=[target_layer])
        input_tensor = face_tensor.unsqueeze(0).float()
        grayscale_cam = cam(input_tensor=input_tensor)[0]

        rgb_face = cv2.cvtColor(face_bgr, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        rgb_face = cv2.resize(rgb_face, (grayscale_cam.shape[1], grayscale_cam.shape[0]))
        overlay = show_cam_on_image(rgb_face, grayscale_cam, use_rgb=True)

        success, buf = cv2.imencode(".png", cv2.cvtColor(overlay, cv2.COLOR_RGB2BGR))
        if not success:
            return None
        return base64.b64encode(buf.tobytes()).decode("ascii")
    except Exception:
        return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-dir", required=True, type=Path)
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    model = load_model(args.repo_dir, args.checkpoint)
    result = run_inference(model, args.repo_dir, args.input)

    score = result["overall_score"]
    evidence = []
    evidence_image = None
    if score >= EVIDENCE_SCORE_THRESHOLD:
        evidence = build_evidence(result["frame_idxs"], result["frame_scores"], result["fps"])
        evidence_image = try_generate_heatmap(
            result["model_ref"], result["worst_frame_tensor"], result["worst_frame_bgr"]
        )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps({"score": score, "evidence": evidence, "evidence_image": evidence_image}, ensure_ascii=False),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 커밋**

```bash
git add scripts/dfdc_infer.py
git commit -m "feat(detection): 영상(selimsef/dfdc) 커스텀 추론 스크립트 추가 (데스크탑 검증 전)"
```

이 태스크까지 완료되면 Spring + Python 코드는 전부 끝난다. 다음은 사용자가 데스크탑에서 진행할 실제 셋업/검증 단계다 (아래 "구현/배포 흐름" 참고, 이 플랜 범위 밖).

---

## 구현/배포 흐름 (플랜 밖, 참고용)

Task 10까지 끝나면 두 레포 모두 로컬 커밋이 완료된 상태다. 이후 흐름(스펙 §12 참고, 이 플랜의 태스크가 아니라 사용자가 직접 진행):

1. 두 레포 `git push`
2. 데스크탑에서 `git pull` + 새 `dfdc` conda env 생성(`opencv-python`, `facenet-pytorch`, `pytorch-grad-cam`, `timm`, `albumentations` 등 selimsef `requirements.txt` 기준 설치) + selimsef 저장소 클론 + `download_weights.sh`로 가중치 다운로드
3. `DFDC_REPO_DIR`, `DFDC_CHECKPOINT` 등 환경변수 설정 후 `detection-api` 서버 재시작(Python `Settings`가 `@lru_cache`라 재시작 필요 - 기존 SPAI/AntiDeepfake와 동일)
4. 실제 영상으로 데스크탑에서 직접 스크립트 실행 → Grad-CAM이 실제로 말이 되는 히트맵을 내는지, `target_layer` 경로(`model.encoder.conv_head`)가 실제 모델 구조와 맞는지, 타임아웃(600s)이 충분한지 확인 → 문제 있으면 `scripts/dfdc_infer.py`/`config.py` 수정
5. Spring 쪽 `DETECTION_SERVICE_URL`은 이미지/음성과 공유되므로 추가 설정 불필요 - `POST /api/v1/analysis/video` → `GET /api/v1/analysis/jobs/{jobId}` 흐름을 Swagger UI로 실제 데스크탑 서버 붙여서 end-to-end 테스트
