# 회원별 분석기록/리포트 — 설계

- **작성일**: 2026-09-23
- **관련 문서**: `docs/superpowers/specs/2026-08-26-video-ai-detection-design.md` (기존 `AnalysisJob` 설계 배경)
- **관련 메모리**: `project_veritae_current_work`(로드맵 ③번 항목)

## 1. 배경 및 목적

지금까지 이미지/음성/영상 AI판독·사기감지 기능은 모두 구현됐지만, **회원이 자신의 과거 분석 결과를 다시 조회할 방법이 없다**. 영상만 `AnalysisJob` 테이블에 저장되고(비동기 처리 중 서버 재시작에도 안전하게 하려는 목적으로 설계됨, §5 참고), 이미지/음성은 동기 응답 후 결과가 즉시 소실된다.

이번 작업의 목적: 세 모달리티 전체의 분석 결과를 회원별로 저장하고, (1) 이력 목록 조회, (2) 통계 리포트 조회 — 이 두 기능을 분리된 API로 제공한다.

## 2. 스코프 확정 사항 (2026-09-23 세션에서 논의 후 확정)

- **범위**: 이미지·음성·영상 전체. 이미지/음성 분석 API는 이미 `/api/v1/analysis/**` 전체가 `SecurityConfig`에서 `.authenticated()`로 막혀 있어 로그인은 이미 필수였다 — 이번에 새로 인증을 추가하는 게 아니라, 이미 인증된 요청에서 memberId를 꺼내 저장에 연결하는 작업이다.
- **보관 정책**: 개수 제한/삭제 없음. 전부 저장하고 조회 시 페이지네이션으로 필요한 만큼만 보여준다. 현재 데이터 규모(회원 11명, 영상기록 18건)와 예상 성장 속도를 감안하면 저장 비용은 문제되지 않는다.
- **이력 목록에는 완료(COMPLETED)된 것만 표시**. 처리중/실패 상태는 기존 job 폴링 조회(`GET /api/v1/analysis/jobs/{jobId}`)에서만 노출된다.
- **리포트는 별도 API로 분리**한다 (목록 API에 통계를 얹지 않음).
- **리포트 형태는 온디맨드 집계 JSON**이다. PDF 등 파일 생성은 하지 않는다 — 이 서버의 클라이언트는 iOS 앱이라, 통계 데이터를 주면 화면에서 직접 그리는 게 자연스럽다.

## 3. 데이터 모델

기존 `analysis_jobs` 테이블(엔티티 `AnalysisJob`)을 그대로 확장한다 — **새 테이블을 만들지 않는다.**

**검토했던 대안(기각)**: 이미지/음성용 별도 테이블을 신설하고 조회 시 두 테이블을 합치는 방식도 검토했으나, "회원별 통합 이력 조회"라는 핵심 요구사항이 결국 두 결과를 매번 합쳐야 하는 구조가 되어 오히려 복잡해진다. 기존 테이블에 컬럼만 추가하는 쪽이 조회 로직을 훨씬 단순하게 만든다(쿼리 하나로 끝).

**추가되는 컬럼:**

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `modality` | enum (STRING): `IMAGE`, `AUDIO`, `VIDEO` | 어떤 분석인지 구분 |
| `ai_score` | double, nullable | AI판독 점수 — 리포트 집계용으로 결과 JSON에서 추출해 별도 저장 |
| `scam_score` | double, nullable | 사기감지 점수 — 텍스트 미추출 시 null(기존 `ScamDetectionResult` 자체가 null인 경우와 동일) |

`ai_score`/`scam_score`를 별도 컬럼으로 뽑아두는 이유: 리포트 API가 "이번 달 총 몇 건", "사기 위험 탐지 몇 건" 같은 집계를 낼 때, `result_json`(LONGTEXT) 안의 내용을 매번 파싱하지 않고 이 두 컬럼만으로 바로 집계 쿼리를 돌릴 수 있게 하기 위함이다. 목록 API는 지금처럼 `result_json` 전체를 그대로 보여준다.

**기존 컬럼(`status`, `error_code`, `error_message`)의 이미지/음성 처리 방식**: 이미지/음성은 동기 처리라 상태 전이가 없다 — 분석이 성공한 순간 `status=COMPLETED`로 한 번에 저장한다(실패 시에는 애초에 저장하지 않고 기존처럼 즉시 4xx/5xx 응답, §5 참고). 영상은 기존 PENDING→PROCESSING→COMPLETED/FAILED 흐름을 그대로 유지한다.

**리네이밍**: `AnalysisJob`이라는 이름이 "영상 전용 비동기 작업"이라는 의미로 굳어 있어, 세 모달리티를 다 담는 지금 의미와 맞지 않는다. 다음 클래스들을 `AnalysisRecord`로 리네이밍한다:
- `AnalysisJob` → `AnalysisRecord`
- `AnalysisJobRepository` → `AnalysisRecordRepository`
- `AnalysisJobView` → `AnalysisRecordView` (또는 기존 용도 유지 시 그대로, §6 참고)
- `AnalysisJobNotFoundException` → `AnalysisRecordNotFoundException`

`AnalysisJobStatus`는 상태값(PENDING/PROCESSING/COMPLETED/FAILED)의 의미 자체가 안 바뀌므로 이름은 유지한다.

## 4. API 설계

기존 `/api/v1/analysis/*` 네이밍 관례를 따른다.

### 4-1. 이력 목록 조회

```
GET /api/v1/analysis/records?page=0&size=10
```

- 인증 필요(bearer). 토큰의 memberId로 본인 기록만 조회 — URL에 회원 식별자를 넣지 않는다(기존 `/analysis/jobs/{jobId}`의 소유권 검증 패턴과 동일한 원칙).
- `status=COMPLETED`인 것만, `created_at` 내림차순, 페이지 단위 반환.
- 응답 항목: `id`, `modality`, `createdAt`, 그리고 modality에 맞는 결과 본문(`result_json`을 modality별 타입으로 역직렬화).

### 4-2. 리포트 조회

```
GET /api/v1/analysis/report
```

- 인증 필요. 본인 기록 기준 집계.
- `ai_score`/`scam_score` 컬럼만으로 집계 쿼리(파싱 없이) — 예: 모달리티별 총 건수, 사기 위험 탐지 건수/비율 등. 정확한 집계 항목은 구현 단계에서 iOS 쪽 요구사항 확인 후 확정.

## 5. 컴포넌트 변경 사항

| 파일 | 변경 내용 |
|---|---|
| `domain/analysisjob/AnalysisJob.java` → `AnalysisRecord.java` | `modality`, `ai_score`, `scam_score` 컬럼 추가. 이미지/음성용 새 static factory(예: `completedSync(memberId, modality, resultJson, aiScore, scamScore)`) 추가 — 생성과 동시에 COMPLETED |
| `domain/analysisjob/AnalysisJobRepository.java` → `AnalysisRecordRepository.java` | `findByMemberIdAndStatusOrderByCreatedAtDesc(memberId, status, Pageable)` 추가 (이력 목록용), 리포트용 집계 쿼리 추가 |
| `analysis/ImageAnalysisService.java`, `AudioAnalysisService.java` | 분석 성공 후 결과를 `AnalysisRecord`로 저장하는 로직 추가 |
| `api/AnalysisApiDelegateImpl.java` | `analyzeImage`/`analyzeAudio`가 `AuthenticatedMemberResolver.currentMemberId()`를 사용하도록 변경(영상과 동일 패턴). 이력/리포트 조회 델리게이트 메서드 2개 추가 |
| `api/AnalysisApiMapper.java` | modality별 결과 역직렬화 분기를 한 곳에 모아 처리하는 헬퍼 추가, 이력/리포트 응답 매핑 추가 |
| `analysis/AnalysisJobView.java` | 이름/용도 재검토 (기존 job 폴링 조회 응답과 새 이력 목록 응답이 다른 모양이라 별도 뷰 타입이 필요할 수 있음 — 구현 단계에서 확정) |
| `StaleAnalysisJobCleaner.java` | 변경 불필요 — 이미지/음성은 PENDING/PROCESSING 상태로 저장되는 순간이 없어 이 정리 로직 대상에 자동으로 제외됨 |
| `openapi.yaml` | `/api/v1/analysis/records`, `/api/v1/analysis/report` 엔드포인트 및 응답 스키마 추가 |

## 6. 에러 처리

기존 패턴과 동일:
- 이미지/음성 분석 자체가 실패하면(빈 파일, 지원 안 하는 포맷, 탐지 서버 오류 등) 지금처럼 즉시 4xx/5xx 응답 — 이 경우 `AnalysisRecord`에 아무것도 저장하지 않는다(성공한 분석만 기록에 남음).
- 다른 회원의 기록을 조회하려는 시도는 애초에 발생하지 않는다 — 이력/리포트 조회가 URL에 회원 식별자를 받지 않고 토큰의 memberId로만 필터링하기 때문.

## 7. 스코프 밖 (의도적으로 제외)

- **최근 N건만 유지하는 보관 정책** — 검토 후 기각. 전부 저장 + 페이지네이션이 더 단순하고, 데이터 규모상 불필요.
- **PDF/파일 형태의 리포트 생성** — 검토 후 기각. 온디맨드 JSON 집계로 충분.
- **리포트 집계용 컬럼을 더 세분화(예: 월별/카테고리별 사전 집계 테이블)** — 지금 데이터 규모에서 불필요. 실제로 느려지면 그때 추가.
- **`analysis_jobs` 테이블을 완전히 새 테이블로 분리** — 검토 후 기각(§3 참고).

## 8. 테스트

기존 계층별 테스트 패턴을 따른다:
- `AnalysisRecordRepositoryTest`(`@DataJpaTest`) — modality/status 조합 저장·조회, 페이지네이션, 리포트 집계 쿼리
- `ImageAnalysisServiceTest`/`AudioAnalysisServiceTest` — 분석 성공 시 기록 저장 확인
- `AnalysisApiControllerTest`(`@WebMvcTest`) — 이력/리포트 엔드포인트, 다른 회원 데이터 미노출 확인
