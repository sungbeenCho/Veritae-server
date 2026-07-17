# ADR-0001: Auth & Member API 계약 (v0.1.0)

- Status: Accepted
- Date: 2026-07-17
- Deciders: 서버 개발(사용자), iOS 팀장(형) — 대화로 요구사항 합의
- Spec: `src/main/resources/openapi.yaml`

## Context

Veritae 의 첫 기능(로그인/회원가입 + 내 정보 조회)에 대한 최초의 공식 API 계약을 확정한다.
리포에는 아직 도메인 코드가 없고(초기 Spring Boot scaffold 만 존재), 정식 PRD 도 없다.
따라서 이 OpenAPI 스펙과 본 ADR 이 이 기능의 최초 요구사항/설계 산출물이다.

ADR 작성 사유(해당 항목):
- **새 endpoint 그룹 신설**: `Auth`, `Members` 두 리소스 그룹 신규.
- **인증/권한 모델 결정**: JWT 기반 stateless 인증 도입.
- **에러 스키마 신설**: RFC 9457 ProblemDetail 공통 에러 포맷 채택.

## Decision

### 엔드포인트 (4개, 범위 고정)
- `POST /api/v1/auth/signup` — 201 / 400 / 409
- `POST /api/v1/auth/login` — 200 / 400 / 401
- `POST /api/v1/auth/refresh` — 200 / 400 / 401
- `GET  /api/v1/members/me` — 200 / 401 (Bearer 인증)

의도적 제외(이번 범위 아님): 비밀번호 변경, 로그아웃, 회원 탈퇴, 소셜 로그인.

### 응답/에러 포맷
- 성공 응답은 envelope 없이 DTO 를 직접 반환한다(openapi-first 템플릿의 기존 패턴).
- 에러 응답은 RFC 9457 `application/problem+json` `ProblemDetail` 공통 스키마로 통일.
  - `problem-details-rfc9457` 스킬이 금지하는 "성공=envelope / 에러=ProblemDetail 혼용"을 피하기 위해
    성공도 raw DTO 로 통일했다. 이는 `rest-api-conventions` 의 `ApiResponse` envelope 권장을
    **의도적으로 오버라이드**한 것이며, 이후 이 스펙을 손대는 사람이 envelope 로 "되돌리지" 않도록
    남겨두는 결정이다.
  - 확장 필드 `errorCode`(기계 판독), `timestamp`(ISO 8601), 검증 오류용 `violations` 포함.

### 인증 모델
- JWT 기반 **stateless**. 서버측 세션/토큰 저장소 없음.
- accessToken 단기(기본 1800초=30분), refreshToken 장기.
- 로그아웃/서버측 토큰 무효화 없음 → accessToken 단기 수명이 완화책.
- `/api/v1/auth/refresh` 는 refreshToken **회전 없이** 새 accessToken 만 발급.

## API surface change

변경 전: 인증/회원 관련 엔드포인트 없음(도메인 코드 자체가 없음).

변경 후: 위 4개 엔드포인트 신설, 공통 `ProblemDetail` 에러 스키마, `bearerAuth` 시큐리티 스킴 도입.
스키마: `SignupRequest`, `LoginRequest`, `RefreshRequest`, `TokenResponse`, `AccessTokenResponse`,
`MemberResponse`, `ProblemDetail`, `Violation`.

## 확정한 세부 결정 (초안에 명시되지 않았던 부분)

| 항목 | 결정 | 근거 |
|---|---|---|
| password 규칙 | 8~20자, 영문+숫자 각 1자 이상 (`^(?=.*[A-Za-z])(?=.*\d).{8,20}$`) | 브리프의 예시 규칙 채택, Spring `@Pattern` 로 강제 가능 |
| nickname 규칙 | 2~10자, 유니코드 문자/숫자(`^[\p{L}\p{N}]{2,10}$`) | 한글 닉네임 허용, 공백/특수문자 배제 |
| accessToken 수명 | 1800초(30분), 응답 `expiresIn` 로 노출 | 브리프의 30분~1시간 범위 중 하한 |
| refreshToken 수명 | 서버 구현값(스펙엔 미노출) | 클라이언트가 만료를 스펙에 의존하지 않게 함 |
| refresh 응답 | accessToken + tokenType + expiresIn (refreshToken 재발급 없음) | 브리프 "새 accessToken 만 반환" |
| member id 타입 | `uuid` 문자열 | rest-api-conventions: 순차 PK 노출 금지, 향후 `/members/{id}` 대비 |
| login 실패 | 이메일 미존재/비번 불일치 모두 401 `INVALID_CREDENTIALS` 로 통일 | 계정 열거(enumeration) 방지 |
| 버전 경로 | `/api/v1/...` | rest-api-conventions 권장을 따름. 도입 비용이 낮고 향후 v2 전환 대비 |

## iOS impact

첫 기능이므로 신규 생성. 영향받는(생성될) 타입/요청:
- 요청 모델: `SignupRequest`, `LoginRequest`, `RefreshRequest` (Encodable).
- 응답 모델: `TokenResponse`, `AccessTokenResponse`, `MemberResponse`, `ProblemDetail`, `Violation` (Decodable).
- `tokenType` 은 `enum: [Bearer]` → Swift 에서 String enum 또는 상수로 매핑 가능.
- 날짜(`timestamp`)는 ISO 8601 문자열 → `date-time` 디코딩 전략 지정 필요.
- 인증 헤더: 보호 API 호출 시 `Authorization: Bearer <accessToken>`.
- 모든 필드에 `nullable` 명시됨 → 응답 non-null 필드는 Swift 비옵셔널로, ProblemDetail 의 `detail/instance/timestamp/violations` 는 옵셔널로 매핑.

## Spring impact

openapi-generator(spring, delegate 패턴)로 인터페이스/DTO 생성 후 delegate 구현:
- Controller: `AuthApi`, `MembersApi` 인터페이스가 생성됨 → `AuthApiDelegateImpl`, `MembersApiDelegateImpl` 구현.
- DTO: 생성된 모델을 그대로 사용(수기 모델과 혼용 금지).
- Validator: 생성 DTO 에 `@Email/@Pattern/@Size/@NotNull` 반영됨 → 컨트롤러에서 `@Valid` 필요.
- Exception/Handler: `@RestControllerAdvice`(`ResponseEntityExceptionHandler` 확장)에서
  `ProblemDetail` 반환. 도메인 예외 `EmailAlreadyExistsException`(409),
  `InvalidCredentialsException`(401), `InvalidRefreshTokenException`(401) 매핑,
  검증 실패는 `handleMethodArgumentNotValid` 에서 `violations` 채움.
- Security: JWT resource server 설정(`bearerAuth`) — `/api/v1/auth/**` 공개, `/api/v1/members/**` 인증 필요.
  비밀번호는 BCrypt 등 단방향 해시 저장.
- 설정: `spring.mvc.problemdetails.enabled: true`.

## Migration steps

BREAKING 아님(신규 계약, 기존 소비자 없음). 초기 채택 절차:
1. openapi-generator-maven/gradle 플러그인에 `src/main/resources/openapi.yaml` 연결(spring generator, delegatePattern=true, useTags=true).
2. 생성 소스는 `.gitignore` 처리(빌드 시 생성).
3. delegate 구현 + 전역 예외 핸들러 + JWT 시큐리티 구성.
4. iOS 는 동일 스펙으로 모델/클라이언트 생성 후 통합 테스트.

## 호환성 분류

SAFE (신규 계약, 기존 클라이언트 없음).

## 향후 과제 / 미결

- 로그아웃/토큰 무효화, refreshToken 회전, 소셜 로그인은 후속 스펙에서 다룬다.
