# 음성 AI 판독(딥페이크 탐지) 기능 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /api/v1/analysis/audio`를 추가해 업로드한 음성이 AI 생성(딥페이크)일 확률과, 왜 그렇게 판단했는지에 대한 시간 구간 근거(evidence)를 함께 반환한다.

**Architecture:** iOS 앱 → Spring(`veritae-server`, 이 레포, 오케스트레이터) → Python 탐지 서버(`veritae-detection-server`, 3060Ti 데스크탑). Spring 쪽은 이미지 API와 동일한 delegate→service→client 패턴을 모달리티별로 나란히 추가한다. Python 쪽은 SPAI와 동일하게 무거운 모델 의존성을 별도 conda env(`antideepfake`)에 격리하고, 가벼운 FastAPI 서버(`detection-api` env)가 subprocess + 파일로 결과를 주고받는다.

**Tech Stack:** Spring Boot(Java), openapi-generator(delegate pattern), FastAPI(Python), AntiDeepfake(`mms_300m` 체크포인트, wav2vec2 계열 SSL + `forward_seg`), fairseq.

**Spec:** `docs/superpowers/specs/2026-08-18-audio-ai-detection-design.md`

## Global Constraints

- 이번 스코프는 AI판독(생성 여부) 이유만 다룬다 — 사기감지(fraud-risk) 분석은 완전히 별도이며 이번 작업에 포함하지 않는다.
- 음성은 동기(sync) API로 유지한다 — 비동기 잡 모델은 영상용으로 별도 스코프.
- Evidence는 시간 구간(temporal)만 지원한다 — 주파수 대역(spectral) 필드는 만들지 않는다.
- Python 쪽 무거운 의존성(fairseq 등)은 `antideepfake` conda env에 격리한다 — `detection-api` env(FastAPI)는 가볍게 유지하고 새 의존성을 추가하지 않는다.
- Claude Code 세션은 이 노트북(veritae-server, veritae-detection-server 로컬 클론)에서만 코드를 작성할 수 있다 — 3060Ti 데스크탑 실행/검증은 사용자가 직접 한다.

---

## Task 1: 이미지 API 파일 크기 제한 버그 수정

기존 이미지 API에 파일 크기 제한이 명시 설정돼 있지 않아 Spring Boot 기본값(1MB)이 적용되고 있었다. 실제 폰 사진(2~10MB)이 업로드 실패할 상태였다 — 음성(25MB 상한 예정)을 추가하기 전에 먼저 고친다.

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java`

**Interfaces:**
- Consumes: 없음 (설정 변경)
- Produces: 없음 (이후 태스크에 영향 없음)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java`에 아래 테스트를 추가한다 (기존 `analyzeImage_withAuthenticatedMemberAndValidFile_shouldReturn200WithScore` 메서드 뒤에):

```java
    @Test
    @WithMockUser
    void analyzeImage_withFileLargerThanSpringDefaultMultipartLimit_shouldNotBeRejectedByFramework()
            throws Exception {
        byte[] largeContent = new byte[5 * 1024 * 1024]; // 5MB > Spring Boot 기본 max-file-size(1MB)
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", largeContent);
        when(imageAnalysisService.analyzeImage(any())).thenReturn(new AiDetectionResult("spai", 0.87));

        mockMvc.perform(multipart("/api/v1/analysis/image").file(file))
                .andExpect(status().isOk());
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.api.AnalysisApiControllerTest"`
Expected: FAIL — `analyzeImage_withFileLargerThanSpringDefaultMultipartLimit_shouldNotBeRejectedByFramework` 가 500(또는 멀티파트 관련 예외)으로 실패

- [ ] **Step 3: 설정 추가**

`src/main/resources/application.properties` 맨 아래(마지막 `detection.read-timeout=60s` 다음)에 추가:

```properties
# 업로드 파일 크기 제한. 기본값(1MB)이 실제 폰 사진(2~10MB)과 음성 파일(최대 25MB)을
# 못 받는 문제가 있어 명시 설정한다. 정밀한 상한(이미지/음성별 최대 용량)은 각 서비스의
# validate() 에서 별도로 검증한다 - 여기는 프레임워크 레벨의 넉넉한 상한선일 뿐이다.
spring.servlet.multipart.max-file-size=30MB
spring.servlet.multipart.max-request-size=30MB
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.api.AnalysisApiControllerTest"`
Expected: PASS (전체)

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/application.properties src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java
git commit -m "fix(analysis): 업로드 파일 크기 제한 기본값(1MB) 문제 수정"
```

---

## Task 2: OpenAPI 계약 확장 — Evidence/AiDetectionResult 스키마 + `/api/v1/analysis/audio` 경로

**Files:**
- Modify: `src/main/resources/openapi.yaml`

**Interfaces:**
- Consumes: 없음
- Produces: 코드 생성 결과 `com.veritae.veritae_server.openapi.model.Evidence(String title, String description, List<String> tags, Double startSec, Double endSec)`, `com.veritae.veritae_server.openapi.model.AiDetectionResult(String model, Double score, List<Evidence> evidence)` (필드 추가), `com.veritae.veritae_server.openapi.model.AudioAnalysisResponse(AiDetectionResult aiDetection)`, `com.veritae.veritae_server.openapi.api.AnalysisApiDelegate.analyzeAudio(MultipartFile file)` (default 501 메서드) — Task 3~6이 이 타입/메서드를 사용한다.

- [ ] **Step 1: `AiDetectionResult` 스키마에 `evidence` 필드 추가**

`src/main/resources/openapi.yaml`의 `AiDetectionResult` 스키마를 찾아 (`required: [model, score]` 로 시작하는 블록) 아래처럼 바꾼다:

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
```

- [ ] **Step 2: `Evidence` 스키마 신규 추가**

같은 `schemas:` 블록 안, `AiDetectionResult` 다음에 추가:

```yaml
    Evidence:
      type: object
      required: [title, description, tags, startSec, endSec]
      properties:
        title:
          type: string
          nullable: false
          description: 근거 카드 제목.
          example: 시간 구간 이상 패턴
        description:
          type: string
          nullable: false
          description: 이 구간이 왜 의심스러운지에 대한 설명.
          example: 0.5초~1.2초 구간에서 합성 흔적이 감지됨
        tags:
          type: array
          nullable: false
          items:
            type: string
          example: [temporal]
        startSec:
          type: number
          format: double
          nullable: false
          minimum: 0
          description: 근거 구간 시작 시각(초).
          example: 0.5
        endSec:
          type: number
          format: double
          nullable: false
          minimum: 0
          description: 근거 구간 종료 시각(초).
          example: 1.2
```

- [ ] **Step 3: `AudioAnalysisResponse` 스키마 신규 추가**

`ImageAnalysisResponse` 스키마 바로 다음에 추가:

```yaml
    AudioAnalysisResponse:
      type: object
      required: [aiDetection]
      properties:
        aiDetection:
          $ref: '#/components/schemas/AiDetectionResult'
```

- [ ] **Step 4: `/api/v1/analysis/audio` 경로 추가**

`paths:` 블록의 `/api/v1/analysis/image:` 항목 바로 다음(줄 158 `default:` 다음, `components:` 앞)에 추가:

```yaml
  /api/v1/analysis/audio:
    post:
      tags: [Analysis]
      operationId: analyzeAudio
      summary: 음성 AI 생성 여부 분석
      description: >-
        업로드한 음성이 AI로 생성(합성)되었을 확률과 판독 근거를 반환한다. 탐지 서버(별도 인프라) 호출이 끝날 때까지
        응답을 기다리는 동기 방식이다.
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
                  description: 분석할 음성 파일(wav/mp3/m4a/aac, 최대 5분/25MB).
      responses:
        '200':
          description: 분석 성공
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AudioAnalysisResponse'
        '400':
          $ref: '#/components/responses/ValidationError'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '502':
          $ref: '#/components/responses/DetectionServiceUnavailable'
        default:
          $ref: '#/components/responses/ServerError'
```

- [ ] **Step 5: 빌드해서 코드 재생성 + 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL — `build/generated/openapi/.../model/Evidence.java`, `AudioAnalysisResponse.java` 생성되고, `AnalysisApiDelegate.java`에 `analyzeAudio` default 메서드(501 반환)가 추가됨. 기존 `AnalysisApiDelegateImpl`은 아직 `analyzeAudio`를 구현 안 했지만 default 메서드가 있어 컴파일은 깨지지 않는다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/resources/openapi.yaml
git commit -m "feat(api): 음성 분석 API 계약 추가 (Evidence, AudioAnalysisResponse, POST /api/v1/analysis/audio)"
```

---

## Task 3: 도메인 타입 확장 — Evidence, AiDetectionResult(evidence 필드), InvalidAudioFileException

**Files:**
- Create: `src/main/java/com/veritae/veritae_server/detection/Evidence.java`
- Modify: `src/main/java/com/veritae/veritae_server/detection/AiDetectionResult.java`
- Modify: `src/main/java/com/veritae/veritae_server/detection/spai/SpaiHttpDetectionClient.java`
- Modify: `src/main/java/com/veritae/veritae_server/api/AnalysisApiMapper.java`
- Create: `src/main/java/com/veritae/veritae_server/analysis/InvalidAudioFileException.java`
- Modify: `src/test/java/com/veritae/veritae_server/analysis/ImageAnalysisServiceTest.java`
- Modify: `src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java`
- Modify: `src/test/java/com/veritae/veritae_server/detection/spai/SpaiHttpDetectionClientTest.java`

**Interfaces:**
- Consumes: Task 2가 만든 `com.veritae.veritae_server.openapi.model.Evidence`/`AiDetectionResult`(3-arg)
- Produces: `com.veritae.veritae_server.detection.Evidence(String title, String description, List<String> tags, Double startSec, Double endSec)`, `com.veritae.veritae_server.detection.AiDetectionResult(String model, double score, List<Evidence> evidence)`(3-arg로 변경), `com.veritae.veritae_server.analysis.InvalidAudioFileException` — Task 4/5/6이 이 타입들을 사용한다.

- [ ] **Step 1: `Evidence` record 작성**

`src/main/java/com/veritae/veritae_server/detection/Evidence.java`:

```java
package com.veritae.veritae_server.detection;

import java.util.List;

/**
 * AI 판독 근거 카드 한 장. 시간 구간(temporal) 근거만 지원한다 - 주파수 대역(spectral)
 * 근거는 이번 스코프에 없다 (docs/superpowers/specs/2026-08-18-audio-ai-detection-design.md §3).
 */
public record Evidence(String title, String description, List<String> tags, Double startSec, Double endSec) {
}
```

- [ ] **Step 2: `AiDetectionResult`에 `evidence` 필드 추가**

`src/main/java/com/veritae/veritae_server/detection/AiDetectionResult.java` 전체를 아래로 교체:

```java
package com.veritae.veritae_server.detection;

import java.util.List;

public record AiDetectionResult(String model, double score, List<Evidence> evidence) {
}
```

- [ ] **Step 3: 기존 호출부를 3-arg 생성자로 업데이트**

`src/main/java/com/veritae/veritae_server/detection/spai/SpaiHttpDetectionClient.java`의 `detectImage` 메서드 안, 아래 줄을:

```java
            return new AiDetectionResult(response.aiDetection().model(), response.aiDetection().score());
```

이렇게 바꾸고(파일 상단에 `import java.util.List;` 추가):

```java
            return new AiDetectionResult(response.aiDetection().model(), response.aiDetection().score(), List.of());
```

- [ ] **Step 4: 테스트 코드의 생성자 호출부도 전부 업데이트**

`src/test/java/com/veritae/veritae_server/analysis/ImageAnalysisServiceTest.java` — `new AiDetectionResult("spai", 0.87)` 를 `new AiDetectionResult("spai", 0.87, java.util.List.of())` 로 교체 (또는 파일 상단에 `import java.util.List;` 추가 후 `List.of()`).

`src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java` — Task 1에서 추가한 것 포함, `new AiDetectionResult("spai", 0.87)`가 나오는 **모든** 곳을 `new AiDetectionResult("spai", 0.87, List.of())`로 교체하고 `import java.util.List;` 추가.

- [ ] **Step 5: `AnalysisApiMapper`가 evidence를 openapi 모델로 매핑하도록 수정**

`src/main/java/com/veritae/veritae_server/api/AnalysisApiMapper.java` 전체를 아래로 교체:

```java
package com.veritae.veritae_server.api;

import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.openapi.model.AudioAnalysisResponse;
import com.veritae.veritae_server.openapi.model.Evidence;
import com.veritae.veritae_server.openapi.model.ImageAnalysisResponse;

import java.util.List;

public final class AnalysisApiMapper {

    private AnalysisApiMapper() {
    }

    public static ImageAnalysisResponse toResponse(AiDetectionResult result) {
        return new ImageAnalysisResponse(toOpenApiResult(result));
    }

    public static AudioAnalysisResponse toAudioResponse(AiDetectionResult result) {
        return new AudioAnalysisResponse(toOpenApiResult(result));
    }

    private static com.veritae.veritae_server.openapi.model.AiDetectionResult toOpenApiResult(AiDetectionResult result) {
        List<Evidence> evidence = result.evidence().stream()
                .map(e -> new Evidence(e.title(), e.description(), e.tags(), e.startSec(), e.endSec()))
                .toList();
        return new com.veritae.veritae_server.openapi.model.AiDetectionResult(result.model(), result.score(), evidence);
    }
}
```

- [ ] **Step 6: `InvalidAudioFileException` 작성**

`src/main/java/com/veritae/veritae_server/analysis/InvalidAudioFileException.java`:

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 업로드된 음성 파일이 비어있거나 지원하지 않는 형식/용량인 경우.
 */
public class InvalidAudioFileException extends DomainException {

    public InvalidAudioFileException(String message) {
        super("INVALID_AUDIO_FILE", HttpStatus.BAD_REQUEST, "Invalid Audio File", message);
    }
}
```

- [ ] **Step 7: 빌드 + 전체 테스트 실행해서 통과 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 전체 테스트 PASS

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/detection/Evidence.java src/main/java/com/veritae/veritae_server/detection/AiDetectionResult.java src/main/java/com/veritae/veritae_server/detection/spai/SpaiHttpDetectionClient.java src/main/java/com/veritae/veritae_server/api/AnalysisApiMapper.java src/main/java/com/veritae/veritae_server/analysis/InvalidAudioFileException.java src/test/java/com/veritae/veritae_server/analysis/ImageAnalysisServiceTest.java src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java
git commit -m "feat(detection): AiDetectionResult에 evidence 필드 추가, InvalidAudioFileException 신설"
```

---

## Task 4: AudioDetectionClient 인터페이스 + AntiDeepfakeHttpDetectionClient 어댑터

**Files:**
- Create: `src/main/java/com/veritae/veritae_server/detection/AudioDetectionClient.java`
- Create: `src/main/java/com/veritae/veritae_server/detection/antideepfake/AntiDeepfakeHttpDetectionClient.java`
- Create: `src/test/java/com/veritae/veritae_server/detection/antideepfake/AntiDeepfakeHttpDetectionClientTest.java`

**Interfaces:**
- Consumes: Task 3의 `detection.AiDetectionResult`/`detection.Evidence`, 기존 `config.DetectionClientConfig`가 제공하는 `RestClient` 빈(재사용, 신규 설정 불필요 — base URL은 `detection.service-url` 그대로, path만 다름)
- Produces: `detection.AudioDetectionClient.detectAudio(byte[] audioBytes, String filename, String contentType): AiDetectionResult` — Task 5가 사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/veritae/veritae_server/detection/antideepfake/AntiDeepfakeHttpDetectionClientTest.java`:

```java
package com.veritae.veritae_server.detection.antideepfake;

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

class AntiDeepfakeHttpDetectionClientTest {

    @Test
    void detectAudio_withSuccessResponse_shouldParseScoreAndEvidence() {
        // Given
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/audio"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        """
                        {"ai_detection": {"model": "antideepfake", "score": 0.87, "evidence": [
                            {"title": "시간 구간 이상 패턴", "description": "0.5초~1.2초 구간에서 합성 흔적이 감지됨",
                             "tags": ["temporal"], "start_sec": 0.5, "end_sec": 1.2}
                        ]}}
                        """,
                        MediaType.APPLICATION_JSON));
        AntiDeepfakeHttpDetectionClient client = new AntiDeepfakeHttpDetectionClient(builder.build());

        // When
        AiDetectionResult result = client.detectAudio("fake-bytes".getBytes(), "test.wav", "audio/wav");

        // Then
        assertThat(result.model()).isEqualTo("antideepfake");
        assertThat(result.score()).isEqualTo(0.87);
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0).title()).isEqualTo("시간 구간 이상 패턴");
        assertThat(result.evidence().get(0).startSec()).isEqualTo(0.5);
        assertThat(result.evidence().get(0).endSec()).isEqualTo(1.2);
        server.verify();
    }

    @Test
    void detectAudio_whenServerReturnsError_shouldThrowDetectionServiceException() {
        // Given
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/audio"))
                .andRespond(withServerError());
        AntiDeepfakeHttpDetectionClient client = new AntiDeepfakeHttpDetectionClient(builder.build());

        // When / Then
        assertThatThrownBy(() -> client.detectAudio("fake-bytes".getBytes(), "test.wav", "audio/wav"))
                .isInstanceOf(DetectionServiceException.class);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.detection.antideepfake.AntiDeepfakeHttpDetectionClientTest"`
Expected: FAIL — `AudioDetectionClient`/`AntiDeepfakeHttpDetectionClient` 클래스가 없어 컴파일 에러

- [ ] **Step 3: `AudioDetectionClient` 인터페이스 작성**

`src/main/java/com/veritae/veritae_server/detection/AudioDetectionClient.java`:

```java
package com.veritae.veritae_server.detection;

/**
 * 음성 AI 생성물 탐지 요청을 추상화하는 포트. 이미지의 {@link DetectionClient}와 별도
 * 인터페이스로 분리한 이유는 docs/superpowers/specs/2026-08-18-audio-ai-detection-design.md §4 참고
 * (영상이 추후 비동기 패턴을 갖게 되면 하나의 인터페이스에 다 몰아넣는 게 부담이 됨).
 */
public interface AudioDetectionClient {

    AiDetectionResult detectAudio(byte[] audioBytes, String filename, String contentType);
}
```

- [ ] **Step 4: `AntiDeepfakeHttpDetectionClient` 어댑터 작성**

`src/main/java/com/veritae/veritae_server/detection/antideepfake/AntiDeepfakeHttpDetectionClient.java`:

```java
package com.veritae.veritae_server.detection.antideepfake;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.detection.AudioDetectionClient;
import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.Evidence;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * {@link AudioDetectionClient} 의 AntiDeepfake(셀프호스팅) 구현체. 탐지 서버(veritae-detection-server,
 * 이미지(SPAI)와 같은 3060Ti 데스크탑)의 POST /process/audio 를 호출한다. 이미지용
 * {@code detectionRestClient} 빈(같은 base URL)을 그대로 재사용한다 - path만 다르다.
 */
@Component
@RequiredArgsConstructor
public class AntiDeepfakeHttpDetectionClient implements AudioDetectionClient {

    private final RestClient detectionRestClient;

    @Override
    public AiDetectionResult detectAudio(byte[] audioBytes, String filename, String contentType) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, MediaType.parseMediaType(contentType));

        try {
            AntiDeepfakeResponse response = detectionRestClient.post()
                    .uri("/process/audio")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(builder.build())
                    .retrieve()
                    .body(AntiDeepfakeResponse.class);

            if (response == null || response.aiDetection() == null) {
                throw new DetectionServiceException("탐지 서버 응답이 비어 있습니다.", null);
            }

            List<Evidence> evidence = response.aiDetection().evidence().stream()
                    .map(e -> new Evidence(e.title(), e.description(), e.tags(), e.startSec(), e.endSec()))
                    .toList();
            return new AiDetectionResult(response.aiDetection().model(), response.aiDetection().score(), evidence);
        } catch (RestClientException e) {
            throw new DetectionServiceException("탐지 서버 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }

    private record AntiDeepfakeResponse(@JsonProperty("ai_detection") AiDetection aiDetection) {
    }

    private record AiDetection(String model, double score, List<EvidenceDto> evidence) {
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

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.detection.antideepfake.AntiDeepfakeHttpDetectionClientTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/detection/AudioDetectionClient.java src/main/java/com/veritae/veritae_server/detection/antideepfake/AntiDeepfakeHttpDetectionClient.java src/test/java/com/veritae/veritae_server/detection/antideepfake/AntiDeepfakeHttpDetectionClientTest.java
git commit -m "feat(detection): 음성 탐지 서버(AntiDeepfake) 호출 클라이언트 추가"
```

---

## Task 5: AudioAnalysisService

**Files:**
- Create: `src/main/java/com/veritae/veritae_server/analysis/AudioAnalysisService.java`
- Create: `src/test/java/com/veritae/veritae_server/analysis/AudioAnalysisServiceTest.java`

**Interfaces:**
- Consumes: Task 4의 `detection.AudioDetectionClient`, Task 3의 `analysis.InvalidAudioFileException`
- Produces: `analysis.AudioAnalysisService.analyzeAudio(MultipartFile file): AiDetectionResult` — Task 6이 사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/veritae/veritae_server/analysis/AudioAnalysisServiceTest.java`:

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.detection.AudioDetectionClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AudioAnalysisServiceTest {

    @Mock
    private AudioDetectionClient audioDetectionClient;

    private AudioAnalysisService audioAnalysisService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        audioAnalysisService = new AudioAnalysisService(audioDetectionClient);
    }

    @Test
    void analyzeAudio_withValidWav_shouldReturnDetectionResult() throws Exception {
        // Given
        var file = new MockMultipartFile("file", "test.wav", "audio/wav", "fake-bytes".getBytes());
        when(audioDetectionClient.detectAudio(file.getBytes(), "test.wav", "audio/wav"))
                .thenReturn(new AiDetectionResult("antideepfake", 0.87, List.of()));

        // When
        AiDetectionResult result = audioAnalysisService.analyzeAudio(file);

        // Then
        assertThat(result.model()).isEqualTo("antideepfake");
        assertThat(result.score()).isEqualTo(0.87);
    }

    @Test
    void analyzeAudio_withEmptyFile_shouldThrowInvalidAudioFileException() {
        // Given
        var file = new MockMultipartFile("file", "test.wav", "audio/wav", new byte[0]);

        // When / Then
        assertThatThrownBy(() -> audioAnalysisService.analyzeAudio(file))
                .isInstanceOf(InvalidAudioFileException.class);
        verifyNoInteractions(audioDetectionClient);
    }

    @Test
    void analyzeAudio_withUnsupportedContentType_shouldThrowInvalidAudioFileException() {
        // Given
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "not-audio".getBytes());

        // When / Then
        assertThatThrownBy(() -> audioAnalysisService.analyzeAudio(file))
                .isInstanceOf(InvalidAudioFileException.class);
        verifyNoInteractions(audioDetectionClient);
    }

    @Test
    void analyzeAudio_withFileLargerThan25Mb_shouldThrowInvalidAudioFileException() {
        // Given
        byte[] tooLarge = new byte[26 * 1024 * 1024];
        var file = new MockMultipartFile("file", "test.wav", "audio/wav", tooLarge);

        // When / Then
        assertThatThrownBy(() -> audioAnalysisService.analyzeAudio(file))
                .isInstanceOf(InvalidAudioFileException.class);
        verifyNoInteractions(audioDetectionClient);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.AudioAnalysisServiceTest"`
Expected: FAIL — `AudioAnalysisService` 클래스가 없어 컴파일 에러

- [ ] **Step 3: `AudioAnalysisService` 구현**

`src/main/java/com/veritae/veritae_server/analysis/AudioAnalysisService.java`:

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.detection.AudioDetectionClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AudioAnalysisService {

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp4", "audio/aac");
    private static final long MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024;

    private final AudioDetectionClient audioDetectionClient;

    public AiDetectionResult analyzeAudio(MultipartFile file) {
        validate(file);
        try {
            return audioDetectionClient.detectAudio(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            throw new UncheckedIOException("업로드된 파일을 읽을 수 없습니다.", e);
        }
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidAudioFileException("빈 파일은 분석할 수 없습니다.");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new InvalidAudioFileException("지원하지 않는 파일 형식입니다: " + file.getContentType());
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidAudioFileException("파일 용량이 25MB를 초과합니다.");
        }
    }
}
```

**참고**: 스펙(§6)에는 "5분 초과" 제약도 있었으나, 정확한 재생 길이를 검증하려면 오디오 디코딩 라이브러리가 추가로 필요하다. 25MB 용량 상한이 wav/mp3/m4a/aac 어떤 포맷으로도 5분 분량을 실질적으로 넘지 않게 막아주므로, 이번 스코프에서는 별도 재생 길이 검증을 만들지 않는다(YAGNI) — 용량 상한이 사실상 같은 역할을 한다.

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.AudioAnalysisServiceTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/analysis/AudioAnalysisService.java src/test/java/com/veritae/veritae_server/analysis/AudioAnalysisServiceTest.java
git commit -m "feat(analysis): AudioAnalysisService 추가 (파일 검증 + 탐지 클라이언트 호출)"
```

---

## Task 6: `analyzeAudio` 엔드포인트 배선 + 통합 테스트

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/api/AnalysisApiDelegateImpl.java`
- Modify: `src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java`

**Interfaces:**
- Consumes: Task 5의 `analysis.AudioAnalysisService`, Task 3의 `api.AnalysisApiMapper.toAudioResponse`, Task 2의 생성된 `AnalysisApiDelegate.analyzeAudio`
- Produces: 없음 (최종 엔드포인트)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java`에 아래를 추가한다 — 클래스 상단 필드에:

```java
    @MockitoBean
    private com.veritae.veritae_server.analysis.AudioAnalysisService audioAnalysisService;
```

그리고 테스트 메서드로 추가:

```java
    @Test
    @WithMockUser
    void analyzeAudio_withAuthenticatedMemberAndValidFile_shouldReturn200WithScoreAndEvidence() throws Exception {
        var file = new MockMultipartFile("file", "test.wav", "audio/wav", "fake-bytes".getBytes());
        when(audioAnalysisService.analyzeAudio(any())).thenReturn(new AiDetectionResult(
                "antideepfake", 0.87,
                List.of(new com.veritae.veritae_server.detection.Evidence(
                        "시간 구간 이상 패턴", "0.5초~1.2초 구간에서 합성 흔적이 감지됨",
                        List.of("temporal"), 0.5, 1.2))));

        mockMvc.perform(multipart("/api/v1/analysis/audio").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiDetection.model").value("antideepfake"))
                .andExpect(jsonPath("$.aiDetection.score").value(0.87))
                .andExpect(jsonPath("$.aiDetection.evidence[0].title").value("시간 구간 이상 패턴"))
                .andExpect(jsonPath("$.aiDetection.evidence[0].startSec").value(0.5));
    }

    @Test
    void analyzeAudio_withoutAuthentication_shouldReturn401() throws Exception {
        var file = new MockMultipartFile("file", "test.wav", "audio/wav", "fake-bytes".getBytes());

        mockMvc.perform(multipart("/api/v1/analysis/audio").file(file))
                .andExpect(status().isUnauthorized());
    }
```

(파일 상단에 이미 `import static org.mockito.Mockito.when;`, `import static org.mockito.ArgumentMatchers.any;` 가 있으므로 추가 import 불필요 — `com.veritae.veritae_server.detection.Evidence`, `java.util.List`만 완전한 경로로 안 쓰려면 상단에 `import java.util.List;`, `import com.veritae.veritae_server.detection.Evidence;` 추가)

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.api.AnalysisApiControllerTest"`
Expected: FAIL — `analyzeAudio_...` 테스트가 501(Not Implemented)로 실패 (delegate가 아직 미구현이라 openapi-generator의 default 메서드가 응답)

- [ ] **Step 3: `AnalysisApiDelegateImpl`에 `analyzeAudio` 구현 추가**

`src/main/java/com/veritae/veritae_server/api/AnalysisApiDelegateImpl.java` 전체를 아래로 교체:

```java
package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AudioAnalysisService;
import com.veritae.veritae_server.analysis.ImageAnalysisService;
import com.veritae.veritae_server.openapi.api.AnalysisApiDelegate;
import com.veritae.veritae_server.openapi.model.AudioAnalysisResponse;
import com.veritae.veritae_server.openapi.model.ImageAnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class AnalysisApiDelegateImpl implements AnalysisApiDelegate {

    private final ImageAnalysisService imageAnalysisService;
    private final AudioAnalysisService audioAnalysisService;

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
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.api.AnalysisApiControllerTest"`
Expected: PASS (전체)

- [ ] **Step 5: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/api/AnalysisApiDelegateImpl.java src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java
git commit -m "feat(analysis): 음성 AI 생성 여부 분석 API 추가 (POST /api/v1/analysis/audio)"
```

이 태스크까지 완료되면 Spring 쪽은 끝난다. Task 7부터는 `veritae-detection-server`(Python) 작업이다.

---

## Task 7: Python — schemas 확장 + `/process/audio` 라우터

**Files:**
- Modify: `C:\Users\sb112\IdeaProjects\veritae-detection-server\app\schemas.py`
- Create: `C:\Users\sb112\IdeaProjects\veritae-detection-server\app\routers\audio.py`
- Modify: `C:\Users\sb112\IdeaProjects\veritae-detection-server\app\main.py`
- Create: `C:\Users\sb112\IdeaProjects\veritae-detection-server\tests\test_audio_router.py`

**Interfaces:**
- Consumes: Task 8이 만들 `app.services.antideepfake_runner.run_antideepfake_inference(audio_bytes, filename) -> AntiDeepfakeResult`, `AntiDeepfakeInferenceError` (이 태스크에서는 monkeypatch로 대체해 테스트하므로 Task 8보다 먼저 작성 가능 — 단, import는 되어 있어야 하므로 Task 8과 순서를 바꿔 실행해도 되지만, import 에러를 피하려면 Task 8을 먼저 완료하고 이 태스크를 실행하는 편이 안전하다. **Task 8을 먼저 실행한 뒤 이 태스크를 실행할 것.**)
- Produces: `POST /process/audio` 엔드포인트 — Spring의 `AntiDeepfakeHttpDetectionClient`(Task 4)가 호출하는 대상.

**참고**: 이 태스크는 Task 8(`antideepfake_runner.py`)의 함수 시그니처에 의존하므로, 실행 순서는 **Task 8 → Task 7** 이 되어야 import 에러가 없다. (Task 번호는 스펙 문서의 컴포넌트 순서를 따랐을 뿐 실행 순서를 강제하지 않는다 — subagent-driven 실행 시 이 의존관계를 지킬 것.)

- [ ] **Step 1: 실패하는 테스트 작성**

`C:\Users\sb112\IdeaProjects\veritae-detection-server\tests\test_audio_router.py`:

```python
import io

from fastapi.testclient import TestClient

from app.main import app
from app.routers import audio as audio_router
from app.services.antideepfake_runner import AntiDeepfakeInferenceError, AntiDeepfakeResult

client = TestClient(app)


def test_process_audio_returns_score_and_evidence(monkeypatch):
    monkeypatch.setattr(
        audio_router,
        "run_antideepfake_inference",
        lambda data, filename: AntiDeepfakeResult(
            score=0.87,
            evidence=[
                {
                    "title": "시간 구간 이상 패턴",
                    "description": "0.5초~1.2초 구간에서 합성 흔적이 감지됨",
                    "tags": ["temporal"],
                    "start_sec": 0.5,
                    "end_sec": 1.2,
                }
            ],
        ),
    )

    response = client.post(
        "/process/audio",
        files={"file": ("test.wav", io.BytesIO(b"fake-audio-bytes"), "audio/wav")},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["ai_detection"]["model"] == "antideepfake"
    assert body["ai_detection"]["score"] == 0.87
    assert body["ai_detection"]["evidence"][0]["title"] == "시간 구간 이상 패턴"
    assert body["ai_detection"]["evidence"][0]["start_sec"] == 0.5


def test_process_audio_rejects_unsupported_content_type():
    response = client.post(
        "/process/audio",
        files={"file": ("test.txt", io.BytesIO(b"not audio"), "text/plain")},
    )

    assert response.status_code == 415


def test_process_audio_rejects_empty_file():
    response = client.post(
        "/process/audio",
        files={"file": ("test.wav", io.BytesIO(b""), "audio/wav")},
    )

    assert response.status_code == 400


def test_process_audio_returns_502_on_inference_failure(monkeypatch):
    def raise_error(data, filename):
        raise AntiDeepfakeInferenceError("boom")

    monkeypatch.setattr(audio_router, "run_antideepfake_inference", raise_error)

    response = client.post(
        "/process/audio",
        files={"file": ("test.wav", io.BytesIO(b"fake-audio-bytes"), "audio/wav")},
    )

    assert response.status_code == 502
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `pytest tests/test_audio_router.py -v`
Expected: FAIL — `app.routers.audio` 모듈이 없어 ImportError

- [ ] **Step 3: `schemas.py`에 Evidence/AudioAnalysisResponse 추가**

`app/schemas.py` 전체를 아래로 교체:

```python
from pydantic import BaseModel


class AIDetectionResult(BaseModel):
    model: str
    score: float


class ImageAnalysisResponse(BaseModel):
    ai_detection: AIDetectionResult


class Evidence(BaseModel):
    title: str
    description: str
    tags: list[str]
    start_sec: float
    end_sec: float


class AudioDetectionResult(BaseModel):
    model: str
    score: float
    evidence: list[Evidence]


class AudioAnalysisResponse(BaseModel):
    ai_detection: AudioDetectionResult
```

- [ ] **Step 4: `app/routers/audio.py` 작성**

```python
from fastapi import APIRouter, File, HTTPException, UploadFile

from app.schemas import AudioAnalysisResponse, AudioDetectionResult, Evidence
from app.services.antideepfake_runner import AntiDeepfakeInferenceError, run_antideepfake_inference

router = APIRouter()

ALLOWED_CONTENT_TYPES = {"audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp4", "audio/aac"}


@router.post("/process/audio", response_model=AudioAnalysisResponse)
async def process_audio(file: UploadFile = File(...)) -> AudioAnalysisResponse:
    if file.content_type not in ALLOWED_CONTENT_TYPES:
        raise HTTPException(
            status_code=415, detail=f"unsupported content type: {file.content_type}"
        )

    audio_bytes = await file.read()
    if not audio_bytes:
        raise HTTPException(status_code=400, detail="empty file")

    try:
        result = run_antideepfake_inference(audio_bytes, file.filename or "input.wav")
    except AntiDeepfakeInferenceError as e:
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
    return AudioAnalysisResponse(
        ai_detection=AudioDetectionResult(model="antideepfake", score=result.score, evidence=evidence)
    )
```

- [ ] **Step 5: `app/main.py`에 라우터 등록**

`app/main.py` 전체를 아래로 교체:

```python
from fastapi import FastAPI

from app.routers import audio, image

app = FastAPI(title="Veritae Detection Server")

app.include_router(image.router)
app.include_router(audio.router)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
```

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `pytest tests/test_audio_router.py -v`
Expected: PASS (4개 전부)

- [ ] **Step 7: 전체 테스트 스위트 실행**

Run: `pytest`
Expected: 전체 PASS (기존 이미지 테스트 포함)

- [ ] **Step 8: 커밋**

```bash
git add app/schemas.py app/routers/audio.py app/main.py tests/test_audio_router.py
git commit -m "feat: 음성 분석 라우터 추가 (POST /process/audio)"
```

---

## Task 8: Python — config 확장 + antideepfake_runner.py (subprocess 래퍼)

**Files:**
- Modify: `C:\Users\sb112\IdeaProjects\veritae-detection-server\app\config.py`
- Create: `C:\Users\sb112\IdeaProjects\veritae-detection-server\app\services\antideepfake_runner.py`
- Create: `C:\Users\sb112\IdeaProjects\veritae-detection-server\tests\test_antideepfake_runner.py`

**Interfaces:**
- Consumes: 없음 (subprocess로 Task 9의 스크립트를 실행하지만, 실행 자체는 mocking되어 테스트에선 스크립트 존재 여부와 무관)
- Produces: `app.services.antideepfake_runner.run_antideepfake_inference(audio_bytes: bytes, filename: str) -> AntiDeepfakeResult`, `AntiDeepfakeResult(score: float, evidence: list[dict])`, `AntiDeepfakeInferenceError` — Task 7이 사용한다(단, Task 7보다 먼저 실행되어야 함, Task 7의 Interfaces 섹션 참고).

**주의**: 이 태스크는 실제 GPU/체크포인트 없이도 전부 자동 테스트 가능하다(subprocess 자체는 mock). 실제 모델이 맞물려 돌아가는지는 Task 9 + Task 10(데스크탑 실행)에서 검증한다 — SPAI 때도 `spai_runner.py`의 로직은 여기서처럼 모킹 테스트로 먼저 검증했고, 실제 동작은 데스크탑에서 확인했다.

- [ ] **Step 1: `config.py`에 AntiDeepfake 설정 추가**

`app/config.py` 전체를 아래로 교체:

```python
import os
from functools import lru_cache
from pathlib import Path


class Settings:
    def __init__(self) -> None:
        repo_dir = os.environ.get("SPAI_REPO_DIR")
        if not repo_dir:
            raise RuntimeError(
                "SPAI_REPO_DIR environment variable is required "
                "(absolute path to the cloned mever-team/spai repo)"
            )
        self.spai_repo_dir = Path(repo_dir)
        self.spai_python = os.environ.get("SPAI_PYTHON", "python")
        self.spai_cfg = os.environ.get("SPAI_CFG", "./configs/spai.yaml")
        self.spai_model = os.environ.get("SPAI_MODEL", "./weights/spai.pth")
        self.spai_timeout_seconds = int(os.environ.get("SPAI_TIMEOUT_SECONDS", "120"))
        # Full-resolution phone photos (3000px+) blow past an 8GB GPU's VRAM
        # in SPAI's patch-based forward pass, so downscale before inference.
        self.spai_resize_to = int(os.environ.get("SPAI_RESIZE_TO", "1024"))
        # Must be absolute: the SPAI subprocess runs with cwd=spai_repo_dir,
        # so a relative path here would resolve against the wrong directory.
        self.work_dir = Path(os.environ.get("SPAI_WORK_DIR", "./tmp")).resolve()
        self.work_dir.mkdir(parents=True, exist_ok=True)

        # --- AntiDeepfake(음성) 설정. SPAI와 마찬가지로 무거운 의존성(fairseq)은
        # 별도 conda env(antideepfake)에 격리하고, 여기(detection-api env)는 subprocess로만 부른다.
        # 이 클래스 하나에 두 모델 설정을 다 넣어서, SPAI_REPO_DIR/ANTIDEEPFAKE_REPO_DIR 둘 다
        # 설정돼야 get_settings()가 성공한다 - 데스크탑에는 어차피 둘 다 상시 구동되므로
        # 실질적 문제는 없지만, 한쪽만 쓰는 격리된 테스트 환경이라면 이 결합이 걸림돌이 될 수 있음(인지하고 있음).
        antideepfake_repo_dir = os.environ.get("ANTIDEEPFAKE_REPO_DIR")
        if not antideepfake_repo_dir:
            raise RuntimeError(
                "ANTIDEEPFAKE_REPO_DIR environment variable is required "
                "(absolute path to the cloned nii-yamagishilab/AntiDeepfake repo)"
            )
        self.antideepfake_repo_dir = Path(antideepfake_repo_dir)
        self.antideepfake_python = os.environ.get("ANTIDEEPFAKE_PYTHON", "python")
        self.antideepfake_script = Path(
            os.environ.get(
                "ANTIDEEPFAKE_SCRIPT",
                str(Path(__file__).resolve().parent.parent.parent / "scripts" / "antideepfake_infer.py"),
            )
        )
        self.antideepfake_checkpoint = Path(
            os.environ.get("ANTIDEEPFAKE_CHECKPOINT", "./downloads/mms_300m.ckpt")
        )
        self.antideepfake_timeout_seconds = int(os.environ.get("ANTIDEEPFAKE_TIMEOUT_SECONDS", "120"))
        self.antideepfake_work_dir = Path(os.environ.get("ANTIDEEPFAKE_WORK_DIR", "./tmp")).resolve()
        self.antideepfake_work_dir.mkdir(parents=True, exist_ok=True)


@lru_cache
def get_settings() -> Settings:
    return Settings()
```

- [ ] **Step 2: 실패하는 테스트 작성**

`C:\Users\sb112\IdeaProjects\veritae-detection-server\tests\test_antideepfake_runner.py`:

```python
import json

import pytest

from app.services.antideepfake_runner import (
    AntiDeepfakeInferenceError,
    _parse_result,
    _safe_filename,
)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("voice.wav", "voice.wav"),
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


def test_parse_result_reads_score_and_evidence(tmp_path):
    output_file = tmp_path / "result.json"
    output_file.write_text(
        json.dumps(
            {
                "score": 0.87,
                "evidence": [
                    {
                        "title": "시간 구간 이상 패턴",
                        "description": "설명",
                        "tags": ["temporal"],
                        "start_sec": 1.0,
                        "end_sec": 2.0,
                    }
                ],
            }
        )
    )

    result = _parse_result(output_file)

    assert result.score == 0.87
    assert result.evidence[0]["title"] == "시간 구간 이상 패턴"


def test_parse_result_missing_score_raises(tmp_path):
    output_file = tmp_path / "result.json"
    output_file.write_text(json.dumps({"evidence": []}))

    with pytest.raises(AntiDeepfakeInferenceError):
        _parse_result(output_file)
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `pytest tests/test_antideepfake_runner.py -v`
Expected: FAIL — `app.services.antideepfake_runner` 모듈이 없어 ImportError

- [ ] **Step 4: `antideepfake_runner.py` 작성**

`app/services/antideepfake_runner.py`:

```python
import json
import shutil
import subprocess
import uuid
from pathlib import Path, PureWindowsPath

from app.config import get_settings


class AntiDeepfakeInferenceError(RuntimeError):
    pass


class AntiDeepfakeResult:
    def __init__(self, score: float, evidence: list[dict]):
        self.score = score
        self.evidence = evidence


def _safe_filename(filename: str) -> str:
    # PureWindowsPath treats both / and \ as separators, so this strips any
    # directory components regardless of host OS, preventing a crafted upload
    # filename from writing outside the job's temp dir. (spai_runner.py와 동일 로직)
    name = PureWindowsPath(filename).name
    return name if name and name not in (".", "..") else "upload"


def run_antideepfake_inference(audio_bytes: bytes, filename: str) -> AntiDeepfakeResult:
    settings = get_settings()
    job_dir = settings.antideepfake_work_dir / uuid.uuid4().hex
    job_dir.mkdir(parents=True, exist_ok=True)
    input_file = job_dir / _safe_filename(filename)
    output_file = job_dir / "result.json"
    input_file.write_bytes(audio_bytes)

    command = [
        settings.antideepfake_python,
        str(settings.antideepfake_script),
        "--repo-dir", str(settings.antideepfake_repo_dir),
        "--checkpoint", str(settings.antideepfake_checkpoint),
        "--input", str(input_file),
        "--output", str(output_file),
    ]

    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=settings.antideepfake_timeout_seconds,
        )

        if result.returncode != 0:
            raise AntiDeepfakeInferenceError(f"AntiDeepfake inference failed: {result.stderr[-2000:]}")

        if not output_file.exists():
            raise AntiDeepfakeInferenceError(f"expected output JSON not found: {output_file}")

        return _parse_result(output_file)
    except subprocess.TimeoutExpired as e:
        raise AntiDeepfakeInferenceError(
            f"AntiDeepfake inference timed out after {settings.antideepfake_timeout_seconds}s"
        ) from e
    finally:
        shutil.rmtree(job_dir, ignore_errors=True)


def _parse_result(output_file: Path) -> AntiDeepfakeResult:
    data = json.loads(output_file.read_text())
    if "score" not in data:
        raise AntiDeepfakeInferenceError(f"AntiDeepfake output JSON missing 'score' field: {output_file}")
    return AntiDeepfakeResult(score=data["score"], evidence=data.get("evidence", []))
```

**참고**: `_safe_filename`은 `spai_runner.py`에 이미 있는 것과 완전히 동일한 로직을 중복 작성했다. 공용 모듈로 뽑을 수도 있었지만, 이미 안정적으로 동작하는 `spai_runner.py`를 이번 스코프에서 건드리지 않기 위해 의도적으로 중복을 택했다(관련 없는 리팩터링 지양).

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `pytest tests/test_antideepfake_runner.py -v`
Expected: PASS (전체)

- [ ] **Step 6: 전체 테스트 스위트 실행**

Run: `pytest`
Expected: 전체 PASS

- [ ] **Step 7: 커밋**

```bash
git add app/config.py app/services/antideepfake_runner.py tests/test_antideepfake_runner.py
git commit -m "feat: AntiDeepfake subprocess 래퍼 추가 (SPAI와 동일한 격리 패턴)"
```

이제 Task 7을 실행한다 (아직 안 했다면).

---

## Task 9: Python — antideepfake_infer.py 추론 스크립트 (antideepfake conda env용)

이 스크립트는 `detection-api` env가 아니라 **`antideepfake` conda env**(fairseq 등 무거운 의존성 설치됨)에서 실행된다. AntiDeepfake 저장소(`nii-yamagishilab/AntiDeepfake`)의 `models/W2V.py`(`Model`, `forward_seg`)와 `utils.py`(`load_weights`)를 그대로 가져다 쓴다.

**중요 — 실증 검증 필요**: 아래 코드는 AntiDeepfake의 공개 소스코드를 읽고 작성한 최선의 추정이지만, 실제 체크포인트로 돌려보지 않으면 확신할 수 없는 부분이 3곳 있다(주석에 표시). SPAI 때도 코드 자체는 미리 다 작성했지만 실제 데스크탑에서 돌려보고서야 3개의 실제 버그(누락 패키지, 상대경로 문제, VRAM 초과)를 발견해 고쳤다 — 이번에도 같은 종류의 실증 확인이 필요할 것으로 예상한다.

**Files:**
- Create: `C:\Users\sb112\IdeaProjects\veritae-detection-server\scripts\antideepfake_infer.py`

**Interfaces:**
- Consumes: 없음 (독립 실행 스크립트)
- Produces: CLI `python antideepfake_infer.py --repo-dir <dir> --checkpoint <path> --input <audio-file> --output <json-path>` — Task 8의 `antideepfake_runner.py`가 subprocess로 호출.

- [ ] **Step 1: 스크립트 작성**

`scripts/antideepfake_infer.py`:

```python
#!/usr/bin/env python
"""antideepfake_infer.py

AntiDeepfake(mms_300m 체크포인트)로 오디오 파일 하나를 추론해서, 전체 score와
시간 구간별(frame-level) evidence를 JSON으로 저장한다.

`antideepfake` conda env(fairseq 등 설치됨)에서 실행되어야 한다.
veritae-detection-server(FastAPI, detection-api env)는 이 스크립트를 subprocess로
호출하고 --output 경로의 JSON만 읽는다 - 무거운 의존성을 FastAPI 프로세스에
넣지 않기 위함 (SPAI 연동과 동일한 패턴).

docs(veritae-server 레포): docs/superpowers/specs/2026-08-18-audio-ai-detection-design.md §3, §4
"""
import argparse
import json
import sys
from pathlib import Path

import torch
import torchaudio

SAMPLE_RATE = 16000
# mms_300m의 wav2vec2 계열 SSL 프론트엔드는 16kHz 기준 320 샘플(20ms)마다 프레임 하나를
# 뽑는다(wav2vec2 계열 컨볼루션 feature encoder의 공통 stride) - 실제 체크포인트로 검증 필요.
FRAME_STRIDE_SECONDS = 0.02
EVIDENCE_SCORE_THRESHOLD = 0.5
MAX_EVIDENCE_COUNT = 3
# forward_seg()가 반환하는 2-class logit 중 어느 인덱스가 "fake"인지 - AntiDeepfake
# protocols(ASVspoof 계열 관례상 보통 1=spoof/fake)를 따랐으나 실제 체크포인트로 검증 필요.
FAKE_CLASS_INDEX = 1


def _safe_filename(filename: str) -> str:
    from pathlib import PureWindowsPath

    name = PureWindowsPath(filename).name
    return name if name and name not in (".", "..") else "upload"


def load_model(repo_dir: Path, checkpoint_path: Path) -> torch.nn.Module:
    sys.path.insert(0, str(repo_dir))
    from models.W2V import Model  # AntiDeepfake 저장소 코드 (repo_dir/models/W2V.py)
    from utils import load_weights  # AntiDeepfake 저장소 코드 (repo_dir/utils.py)

    model = Model(model_name="mms_300m")
    model.eval()
    state_dict = model.state_dict()
    # load_weights는 state_dict를 in-place로 채우는지 반환값을 써야 하는지 README/코드만으로는
    # 100% 확정하지 못했다 - 데스크탑에서 실제 체크포인트로 검증 필요.
    load_weights(state_dict, str(checkpoint_path))
    model.load_state_dict(state_dict)
    return model


def run_inference(model: torch.nn.Module, audio_path: Path) -> dict:
    waveform, sr = torchaudio.load(str(audio_path))
    if sr != SAMPLE_RATE:
        waveform = torchaudio.functional.resample(waveform, sr, SAMPLE_RATE)
    if waveform.size(0) > 1:
        waveform = waveform.mean(dim=0, keepdim=True)

    with torch.no_grad():
        # forward_seg: 1번째 행 = 전체 오디오 예측, 나머지 행 = 프레임별 예측
        # (models/W2V.py Model.forward_seg 참고)
        seg_pred = model.forward_seg(waveform)

    probs = torch.softmax(seg_pred, dim=-1)
    overall_score = probs[0, FAKE_CLASS_INDEX].item()
    frame_scores = probs[1:, FAKE_CLASS_INDEX].tolist()

    return {"overall_score": overall_score, "frame_scores": frame_scores}


def build_evidence(frame_scores: list[float]) -> list[dict]:
    """프레임별 점수에서 임계값(0.5) 이상인 연속 구간을 병합해 근거 카드로 변환한다.
    구간을 점수 최댓값 기준 정렬해 상위 3개만 남긴다."""
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
    for start_idx, end_idx, _peak in top_segments:
        start_sec = round(start_idx * FRAME_STRIDE_SECONDS, 2)
        end_sec = round((end_idx + 1) * FRAME_STRIDE_SECONDS, 2)
        evidence.append(
            {
                "title": "시간 구간 이상 패턴",
                "description": f"{start_sec:.1f}초~{end_sec:.1f}초 구간에서 합성 흔적이 감지됨",
                "tags": ["temporal"],
                "start_sec": start_sec,
                "end_sec": end_sec,
            }
        )
    return evidence


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-dir", required=True, type=Path)
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    model = load_model(args.repo_dir, args.checkpoint)
    result = run_inference(model, args.input)

    score = result["overall_score"]
    evidence = build_evidence(result["frame_scores"]) if score >= EVIDENCE_SCORE_THRESHOLD else []

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"score": score, "evidence": evidence}, ensure_ascii=False))


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 정적 검증(구문/스타일)**

이 스크립트는 fairseq/torch가 이 노트북에 설치돼 있지 않아 실제 실행은 불가능하다. 구문 오류만 확인한다.

Run: `python -m py_compile scripts/antideepfake_infer.py`
Expected: 에러 없이 종료(構文 오류 없음 확인) — 실제 동작 검증은 Task 10에서 데스크탑에 배포한 뒤 진행한다.

- [ ] **Step 3: 커밋**

```bash
git add scripts/antideepfake_infer.py
git commit -m "feat: AntiDeepfake 추론 스크립트 추가 (antideepfake conda env에서 실행)"
```

---

## Task 10: 데스크탑 셋업 가이드 작성

**Files:**
- Modify: `C:\Users\sb112\IdeaProjects\veritae-detection-server\README.md`

**Interfaces:**
- Consumes: 없음
- Produces: 없음 (문서 산출물, 사용자가 데스크탑에서 실행)

- [ ] **Step 1: README에 음성(AntiDeepfake) 셋업 섹션 추가**

기존 README의 SPAI 셋업 섹션 형식을 그대로 따라 새 섹션을 추가한다. 정확한 명령어는 README를 열어 SPAI 섹션의 실제 구조(제목 레벨, 코드블록 스타일)를 확인한 뒤, 아래 내용을 그 형식에 맞춰 작성한다:

1. `antideepfake` conda env 생성 (`conda create --name antideepfake python==3.9.0`)
2. torch/torchaudio **CPU 전용 빌드** 설치 (`pip install torch==2.6.0 torchaudio==2.6.0` — SPAI의 `--index-url .../cu118`은 붙이지 않음, 형 문서 §6 근거로 음성은 CPU로 충분)
3. fairseq 특정 커밋 체크아웃 + editable 설치 (AntiDeepfake README `Installation` 섹션 그대로: `git checkout 862efab86f649c04ea31545ce28d13c59560113d`)
4. speechbrain 등 나머지 패키지 설치
5. AntiDeepfake 저장소 클론 (`git clone https://github.com/nii-yamagishilab/AntiDeepfake.git`)
6. `mms_300m.ckpt` 체크포인트 다운로드 (`wget -O downloads/mms_300m.ckpt https://zenodo.org/records/15580543/files/mms_300m.ckpt`)
7. `veritae-detection-server` 쪽 `git pull`
8. 환경변수 설정: `ANTIDEEPFAKE_REPO_DIR`(클론 경로), `ANTIDEEPFAKE_CHECKPOINT`(체크포인트 경로), `ANTIDEEPFAKE_PYTHON`(`antideepfake` env의 python 실행 파일 절대경로 — `conda activate antideepfake` 후 `where python` 또는 `which python`으로 확인)
9. `detection-api` env(기존)에서 서버 재시작
10. `curl -X POST http://localhost:8000/process/audio -F "file=@테스트음성.wav"`로 실제 호출 확인 — 이 단계에서 Task 9의 3가지 미검증 지점(frame stride, FAKE_CLASS_INDEX, load_weights 반환 방식)이 실제로 맞는지 드러난다. 에러가 나거나 score가 이상하면(예: 항상 0 또는 1) 이 3곳부터 의심할 것.

- [ ] **Step 2: 커밋**

```bash
git add README.md
git commit -m "docs: 음성(AntiDeepfake) 데스크탑 셋업 가이드 추가"
```

---

## 실행 순서 요약

Spring(`veritae-server`): Task 1 → 2 → 3 → 4 → 5 → 6 (순서대로, 서로 의존)
Python(`veritae-detection-server`): Task 8 → 7 (7이 8에 의존) → 9 → 10

Spring과 Python 두 트랙은 서로 독립적이라 병렬로 진행 가능하다.
