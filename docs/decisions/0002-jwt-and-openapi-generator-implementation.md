# ADR-0002: Auth/Member 구현 세부 결정 (JWT 라이브러리, 시크릿 관리, openapi-generator 빌드 설정)

- Status: Accepted
- Date: 2026-07-17
- Deciders: 서버 개발(사용자) — 구현 중 실측 기반으로 결정
- Spec: `src/main/resources/openapi.yaml` (ADR-0001 의 계약을 그대로 구현)
- 관련: `docs/decisions/0001-api-auth-and-member.md`

## Context

ADR-0001 은 API 계약(엔드포인트/에러 포맷/인증 모델)을 확정했지만, 구현 라이브러리 선택과 일부 빌드
설정은 미결로 남겨두었다. 이 ADR 은 실제 구현/빌드/테스트 과정에서 확정한 사항을 기록한다. 상당수는
문서만으로는 알 수 없었고 `./gradlew openApiGenerate` / `compileJava` / `test` 를 실제로 돌려보며
검증한 뒤 확정했다.

## Decision

### JWT 라이브러리: `io.jsonwebtoken:jjwt` (jjwt-api/jjwt-impl/jjwt-gson) 0.13.0

- `spring-security-jwt` 스킬이 예시로 든 jjwt 를 채택했다. 대안이었던
  `spring-boot-starter-oauth2-resource-server` 는 기각했다 — 이 스펙은 access/refresh 두 종류의
  자체 발급 JWT 와 "이메일 미존재/비밀번호 불일치를 구분하지 않는 401" 같은 커스텀 인증 흐름이 필요해,
  외부 OIDC/OAuth2 IdP 를 전제로 하는 resource-server 모델보다 직접 발급/검증하는 jjwt 가 이번
  스펙에 더 맞다고 판단했다.
- JSON 처리 구현체는 `jjwt-jackson` 대신 `jjwt-gson` 을 선택했다. Spring Boot 4 는 기본 JSON 스택이
  Jackson 3(`tools.jackson.*`)이라, jjwt 내부적으로 별도의 Jackson 2(`com.fasterxml.jackson.*`)
  스택을 추가로 끌어들이지 않기 위한 선택이다(실제로는 Jackson 2/3 가 그룹ID/패키지가 달라 공존에
  문제는 없지만, 불필요한 두 번째 JSON 라이브러리를 앱 클래스패스에 추가하지 않는 쪽을 택함).

### JWT 시크릿/서명키 관리

- `app.jwt.secret=${JWT_SECRET:}` 형태로 `application.properties` 에 둔다. 코드에 하드코딩하지
  않는다(spring-security-jwt 스킬의 핵심 금지사항).
- **2026-07-17 수정 (spring-reviewer 리뷰 M3 반영):** 최초 구현은 콜론 뒤에 고정된 base64 키를
  로컬 개발용 기본값으로 커밋해두는 방식이었다. 리뷰에서 "이 기본값이 그대로 운영에 올라가면 레포를
  본 누구나 그 키로 임의 회원의 토큰을 위조할 수 있다"는 지적을 받아, **커밋된 고정 키 자체를
  제거**했다. 대신 `JWT_SECRET` 이 비어있으면 `JwtTokenProvider` 가 기동 시(`@PostConstruct`)
  `SecureRandom` 으로 256bit 임시 키를 생성해 사용한다 — 이 키는 프로세스 재시작마다 바뀌므로
  재시작 시 기존 토큰이 전부 무효화된다. "위조 가능한 고정 키가 조용히 쓰이는 것"보다 "환경변수를
  깜빡하면 사용자가 계속 로그아웃되는 것"이 훨씬 안전하게 드러나는 실패 방식이라 이쪽을 택했다.
- HS256 서명에 필요한 최소 256bit 를 만족하는 base64 문자열을 `JWT_SECRET` 으로 넣어야 한다
  (그렇지 않으면 `WeakKeyException` 발생 — 임시 키 생성 경로는 항상 32바이트를 채우므로 해당 없음).
- accessToken 수명 1800초(30분, ADR-0001 확정값), refreshToken 수명은 스펙에 노출하지 않는 서버
  구현값으로 14일(1,209,600초)로 정했다.

### 로그인 흐름: AuthenticationManager/UserDetailsService 미사용

- `spring-security-jwt` 스킬 예시는 `UserDetailsService` + `DaoAuthenticationProvider` +
  `AuthenticationManager` 를 통해 로그인을 처리하지만, 이번 구현은 `AuthService` 에서
  `PasswordEncoder.matches()` 를 직접 호출하는 방식으로 단순화했다.
- 사유: AC-2 는 "이메일 미존재/비밀번호 불일치를 구분하지 않고 동일한 401" 을 요구한다.
  `UserDetailsService` 경로를 쓰면 `UsernameNotFoundException` 과 `BadCredentialsException` 을
  다시 하나로 합치는 별도 매핑이 필요해지므로, 처음부터 한 곳(AuthService)에서 직접 검증하는 편이
  더 단순하고 실수 여지가 적다고 판단했다.
- 보호 엔드포인트(`/api/v1/members/me`) 는 `JwtAuthenticationFilter` 가 SecurityContext 에
  `principal = memberId(UUID)` 를 직접 심는 방식으로 처리한다(별도 `UserDetails` 조회 없음) — 요청마다
  DB 조회를 하지 않는 stateless 원칙에 더 부합한다.
- `@EnableMethodSecurity`/`@PreAuthorize` 는 추가하지 않았다 — 이번 스펙에 역할(RBAC) 개념이 없어
  사용하지 않을 기능을 미리 열어두지 않았다.

### openapi-generator Gradle 플러그인 설정 (문서와 실측이 달랐던 부분)

`org.openapi.generator` 버전 7.23.0, `generatorName=spring` 기준으로 실측 확인:

| 항목 | 결정 | 근거 |
|---|---|---|
| `useSpringBoot3` vs `useSpringBoot4` | `useSpringBoot4: true` 사용 | 이 리포는 Spring Boot 4.0.6 이고, 7.23.0 부터 `useSpringBoot4` 옵션이 별도로 존재(스킬 문서의 `useSpringBoot3` 예시보다 정확한 타겟) |
| `useJackson3` | `true` | Boot 4 기본 JSON 스택인 Jackson 3 과 맞춤. 단, 실측 결과 생성된 모델의 `@JsonProperty`/`@JsonValue` 등은 여전히 `com.fasterxml.jackson.annotation.*` 를 사용한다 — Jackson 3(`tools.jackson`)의 `jackson-databind` 가 annotations 모듈만은 그대로 `com.fasterxml.jackson.core:jackson-annotations` 를 재사용하기 때문(Jackson 2/3 공용 모듈)이라 실제로는 문제 없음을 확인했다 |
| `skipDefaultInterface` | **`false`로 둠** (스킬은 `true` 권장) | 실측 결과 `true` 로 두면 생성된 `AuthApiController`/`MembersApiController` 의 널세이프 폴백(`new XxxApiDelegate() {}`)이 default 본문 없는 추상 메서드를 구현하지 못해 컴파일이 깨진다. `false` 로 두면 delegate 빈이 없을 때 컴파일 타임이 아닌 런타임 501 로 드러나지만, `AuthApiDelegateImpl`/`MembersApiDelegateImpl` 을 항상 `@Service` 로 등록하므로 실질적 리스크는 없다고 판단 |
| 생성 지원파일 제외 | `generateSupportingFiles`/`ignoreFileOverride`/`supportingFilesConstrainedTo` 대신 Gradle `sourceSets.main.java.exclude(...)` 사용 | `.openapi-generator-ignore` 는 "재생성 시 기존 파일을 덮어쓰지 않는다"는 의미라 매번 새 디렉터리에 생성하는 우리 방식(clean 후 재생성)에는 효과가 없음을 실측으로 확인. `supportingFilesConstrainedTo=[]` 는 이 버전에서 api/model 생성까지 함께 비워버리는 부작용이 있어 폐기. 결국 생성된 `OpenApiGeneratorApplication.java`(중복 `@SpringBootApplication`), `org.openapitools.configuration.*`(중복 SpringDoc/HomeController 설정) 두 개만 Gradle 소스셋에서 컴파일 대상 제외 처리 |

### 부수적으로 고친 사전 존재 버그 (이번 기능 범위는 아니지만 검증을 막고 있어 수정)

- `src/main/resources/openapi.yaml` 의 `bearerAuth.description` 한 줄이 unquoted YAML 스칼라에
  콜론+공백(`Authorization: Bearer`)을 포함해 실제로 YAML 파싱이 깨지는 문법 오류였다. 계약 내용은
  바꾸지 않고 따옴표만 추가해 고쳤다.
- `application.properties` 의 MySQL JDBC URL 에 `allowPublicKeyRetrieval=true` 가 없어 로컬
  MySQL 8(`caching_sha2_password`) 연결 시 `Public Key Retrieval is not allowed` 로 컨텍스트 로딩
  테스트가 실패하고 있었다. 이번 기능과 무관한 기존 로컬 설정 버그이지만, 테스트를 돌리기 위해 함께
  수정했다.

## spring-reviewer 리뷰 반영 (2026-07-17)

`@spring-reviewer` 리뷰에서 나온 지적 중 계약 위반/보안 항목을 반영했다:

- **M1 (ProblemDetail 완전성):** `GlobalExceptionHandler` 가 명시적으로 `@ExceptionHandler` 하지
  않은 예외(잘못된 요청 바디, 405/415/404 등)는 `ResponseEntityExceptionHandler` 기본 구현이 만든
  `ProblemDetail` 에 `errorCode`/`timestamp` 가 빠진 채 나가고 있었다. `handleExceptionInternal` 을
  오버라이드해 모든 에러 응답에 두 필드를 보정하도록 수정.
- **M2 (동시 가입 레이스):** `MemberService.signup` 의 `existsByEmail` → `save` 사이 TOCTOU 로,
  동시 요청 시 DB 유니크 제약 위반이 500 으로 새던 것을 `DataIntegrityViolationException` 을 잡아
  `EmailAlreadyExistsException`(409)으로 변환하도록 수정. **실제로 curl 로 동시 요청 2개를 붙여
  재현하며 검증하는 과정에서, 최초 수정(`save()` 그대로 두고 try/catch 만 추가)은 실제로는 안 먹힌다는
  것이 드러났다** — UUID 는 Java 에서 미리 생성되므로 Hibernate 가 ID 확보를 위해 flush 를 강제할
  이유가 없고, 그래서 평범한 `save()` 는 실제 INSERT 를 트랜잭션 커밋 시점까지 지연시킨다. 그 결과
  유니크 제약 위반이 이 메서드가 반환된 "이후"(커밋 중, try/catch 밖)에 던져져 잡히지 않았다.
  `memberRepository.saveAndFlush(member)` 로 바꿔 INSERT 를 즉시 실행시키도록 수정한 뒤에야
  동시 요청 재현에서 201/409 로 정상 분기됨을 확인했다.
- **M3 (JWT 시크릿 하드코딩):** 위 "JWT 시크릿/서명키 관리" 절 참조.
- **m1 (로그인 타이밍 사이드채널):** "이메일 미존재" 시 BCrypt 비교를 건너뛰어 응답 시간으로 계정
  존재 여부가 새던 것을, 고정 더미 해시(`AuthService.DUMMY_PASSWORD_HASH`)와 항상 비교하도록 수정해
  이메일 존재 여부와 무관하게 매 로그인 시도가 동일한 BCrypt 비용을 지불하게 함.

각 항목에 회귀 테스트를 추가했다(`MemberServiceTest`, `AuthApiControllerTest`, `JwtTokenProviderTest`,
`AuthServiceTest`).

## Swagger UI 가 요청 스키마를 깨뜨리는 문제 (실기동 검증 중 발견, 2026-07-17)

브라우저로 Swagger UI 를 직접 열어 확인하는 과정에서, `signup` 등 요청 바디가 필요한 엔드포인트의
입력 폼이 예시도 없이 그냥 문자열 입력창으로 깨져 나오는 것을 발견했다. 실제 API 자체는(`curl` 로
직접 검증) 정상이었고, 문제는 springdoc 이 컨트롤러를 스캔해 런타임에 재생성하는 `/v3/api-docs` 에만
있었다.

- **원인**: 이 프로젝트는 Spring Boot 4(Jackson 3, `tools.jackson.*`)를 쓰는데, Swagger 문서 생성을
  담당하는 `swagger-core-jakarta`(springdoc 의 의존성)는 아직 Jackson 2(`com.fasterxml.jackson.databind`)
  기반 `ModelResolver` 로 스키마를 조립한다. 두 Jackson 스택이 한 클래스패스에 공존하면서, 런타임
  생성된 `/v3/api-docs` 의 요청 스키마가 `{"type":"string"}` 으로, 응답 스키마도 `$ref` 옆에
  `additionalProperties`/`default` 가 잘못 섞여 나오는 등 깨진 형태로 직렬화됐다.
- **시도했다가 폐기한 방법**: `springdoc.api-docs.enabled=false` 로 아예 껐더니 Swagger UI 자체가
  통째로 사라졌다(swagger-ui 자동설정이 api-docs 활성화에 묶여 있음, 실측 확인). 이 프로퍼티는 쓰지 않는다.
- **채택한 해결책**: 이 프로젝트는 애초에 openapi-first(ADR-0001) 라 손으로 작성한
  `src/main/resources/openapi.yaml` 이 유일한 진실 공급원이다. springdoc 의 런타임 재생성 결과를
  신뢰하는 대신, `OpenApiStaticSpecConfig`(`WebMvcConfigurer`)로 이 정적 파일을 `/openapi.yaml` 경로에
  그대로 노출하고, `springdoc.swagger-ui.url=/openapi.yaml` 로 Swagger UI 가 그 파일을 직접 읽게
  고정했다. `/v3/api-docs` 자체는 그대로 살아있지만(비활성화하면 UI 가 꺼지므로) Swagger UI 는 더 이상
  참조하지 않는다.
- `SecurityConfig` 의 `SWAGGER_PATHS` 에 `/openapi.yaml` 을 추가해 인증 없이 접근 가능하게 했다.

## 호환성 분류

SAFE — 신규 구현 세부사항 확정. 기존 계약(ADR-0001)을 변경하지 않는다.

## 향후 과제 / 미결

- Bean Validation 위반 메시지(`violations[].message`)는 Hibernate Validator 기본 메시지(영문)로
  나간다. openapi.yaml 의 `example` 에 있는 한글 메시지는 문서상 예시일 뿐 강제되지 않는다 — 필드별
  커스텀 메시지가 필요해지면 별도 `ValidationMessages.properties` 작업 필요.
- Flyway/Liquibase 마이그레이션은 도입하지 않았다. 기존 리포 관행대로 `spring.jpa.hibernate.ddl-auto=update`
  를 그대로 사용한다. 운영 환경 전환 시 별도 ADR 필요.
