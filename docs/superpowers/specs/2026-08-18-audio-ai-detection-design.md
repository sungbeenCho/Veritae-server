# 음성 AI 생성(딥페이크) 탐지 기능 — 설계

- **작성일**: 2026-08-18
- **관련 문서**: `C:\Users\sb112\Desktop\deepfake-engine-review.md` (형 작성, 영상/음성 오픈소스 기술검토)
- **관련 메모리**: `project_veritae_analysis_reasoning_requirement`, `project_veritae_detection_architecture`, `feedback_no_llm`

## 1. 배경 및 목적

이미지 AI판독(`POST /api/v1/analysis/image`, SPAI 기반)은 이미 구현되어 있으나 점수만 반환하고 판독 이유(evidence)가 없는 상태다. 이번 작업은 **음성**을 새 모달리티로 추가하면서, 동시에 처음부터 판독 이유(evidence)를 포함하는 것을 목표로 한다.

**스코프 확정 사항**:
- 이번 작업은 **AI판독(생성 여부) 이유만** 다룬다. 사기감지(fraud-risk) 분석은 완전히 별도 파이프라인이며 이번 스코프에서 제외한다 (사기감지는 텍스트 추출(OCR/Whisper) + 텍스트 기반 판별로 계획되어 있었으나, 판별 단계가 원래 LLM 기반이었고 현재 `feedback_no_llm`으로 막혀 있어 미해결 상태 — 별도 세션에서 논의).
- 사기감지는 나중에 AI판독 파이프라인의 출력을 입력으로 받는 형태로 얹히도록 설계되어 있어, 이번 음성 기능과 서로 발목 잡지 않는다.

## 2. 선정 모델

형의 기술검토(`deepfake-engine-review.md` §3, §7)에 따라:
- **음성**: [AntiDeepfake](https://github.com/nii-yamagishilab/AntiDeepfake) (nii-yamagishilab) — 코드 BSD-3, 가중치 CC BY-NC-SA(비상업 전용). 이 프로젝트는 유료화 계획이 없어(2026-08-18 확인) 라이선스 문제 없음. 100+ 언어(한국어 포함) 학습, 활발히 유지보수됨.
- **체크포인트**: `mms_300m` (README의 "Try it out" 데모가 안내하는 플래그십 체크포인트, 형 문서가 평가한 "최고 일반화 성능"도 이 계열 기준). 아키텍처는 `models/W2V.py`의 `Model` — MMS(wav2vec2 계열) SSL 프론트엔드로 프레임별 임베딩을 뽑고 평균 풀링 후 선형 분류. **그래프 어텐션 기반(AASIST 헤드) 구조가 아니다** — 저장소에 `models/AASIST.py`(AASIST 전용 체크포인트)도 있지만, 그건 SSL 없이 스펙트럼 기반이라 형 문서가 평가한 일반화 성능이 적용되지 않는 별도 체크포인트이므로 채택하지 않는다.

## 3. Explainability 스파이크 결과 (2026-08-18 확인, 1차 조사 정정됨)

**1차 조사(오답)**: 처음엔 `models/aasist/AASIST.py`의 그래프 어텐션(temporal/spectral GAT)에서 hook으로 attention을 뽑는 방안을 검토했으나, 이는 위에서 채택하지 않기로 한 AASIST 전용 체크포인트에만 있는 구조라 mms_300m에는 해당하지 않는다.

**정정된 결과**: `models/W2V.py`의 `Model` 클래스에 **이미 구현되어 있는 `forward_seg(wav)` 메서드**를 그대로 쓰면 된다.
- 일반 `forward(wav)`는 SSL이 뽑은 프레임별 임베딩(`emb`, shape `[batch, frame, hidden_dim]`)을 시간축으로 평균 풀링한 뒤 분류 → 오디오 전체에 대한 점수 하나만 나옴
- `forward_seg(wav)`는 풀링 전 **프레임별 임베딩 각각에 분류 헤드(`proj_fc`)를 직접 적용** → 프레임 하나하나에 대한 fake/real 점수가 나옴(shape `[frame, 2]`)
- 즉 hook을 새로 걸 필요 없이 기존 메서드 호출만으로 "몇 번째 프레임(≈몇 초 구간)이 의심스러운지" 시간축 타임라인을 얻을 수 있음
- **한계**: 이 구조엔 주파수 대역(spectral) 축이 없다 — SSL 임베딩은 이미 시간축으로만 나오는 구조라, "어느 주파수 대역이 이상한지"는 이 체크포인트로는 낼 수 없음. **§5의 `Evidence`에서 스펙트럼 필드 제외, 시간 구간(temporal)만 지원**
- 프레임→초 변환 계수(SSL 모델의 frame stride, wav2vec2 계열은 보통 16kHz 기준 20ms/프레임)는 데스크탑에서 실제 체크포인트로 검증 필요 (SPAI 때도 실측 후 확정된 값들이 있었던 것과 동일한 종류의 실증 확인 사항)

## 4. 아키텍처

```
iOS 앱
  → Spring 서버 (오케스트레이터, veritae-server)
      Controller(Delegate) → AudioAnalysisService → AudioDetectionClient(인터페이스) → HTTP 어댑터
  → Python 탐지 서버 (veritae-detection-server, 3060Ti 데스크탑)
      새 라우터 POST /process/audio → subprocess로 별도 스크립트 실행(SPAI와 동일 패턴)
                                     → 결과(score + 시간구간 evidence)를 JSON 파일로 받아 읽음
```

이미지 흐름(`ImageAnalysisService`/`DetectionClient`/`SpaiHttpDetectionClient`)은 변경하지 않는다. 음성은 **완전히 별도의 인터페이스/어댑터**로 나란히 추가한다.

**인터페이스를 이미지와 분리하는 이유**: 영상은 추후 비동기 잡 패턴이 필요해질 예정이라(§8 참고), 이미지/음성과는 흐름 자체가 다르다. 하나의 `DetectionClient` 인터페이스에 모든 모달리티 메서드를 몰아넣으면 한쪽 변경이 다른 쪽에 영향을 준다. 모달리티별로 인터페이스를 쪼개 인터페이스 분리 원칙(ISP)을 지킨다.

### Python 서버 통합 방식 (2026-08-18 정정: SPAI와 동일 패턴으로 확정)

**1차 계획(오답)**: FastAPI 프로세스 안에 모델을 상주시켜 직접 로드하는 방식을 검토했으나, AntiDeepfake의 실제 설치 요구사항(fairseq 특정 구버전 커밋 빌드, speechbrain 등 — SPAI 못지않게 무겁고 예민한 의존성)을 확인한 뒤 폐기.

**확정된 방식**: SPAI(`spai_runner.py`)와 완전히 동일한 패턴.
- 무거운 의존성(fairseq 등)은 SPAI 전용 `spai` conda env와 별개인 **새 `antideepfake` conda env**에 격리
- FastAPI가 도는 `detection-api` env는 계속 가볍게 유지 — torch/fairseq 등을 넣지 않음
- `detection-api`가 `antideepfake` env의 커스텀 추론 스크립트(우리가 직접 작성, AntiDeepfake 저장소 코드를 import해서 체크포인트 로드 + `forward_seg` 호출)를 **subprocess로 실행**, 결과(score + 시간구간별 점수)를 JSON 파일로 저장 → `detection-api`가 그 파일을 읽어 응답 조립
- 형 문서(§6)에 따르면 음성은 CPU 추론으로 충분 — `antideepfake` env의 torch는 **CPU 전용 빌드**로 설치(SPAI의 CUDA 빌드와 별개, 같은 GPU를 두 env가 동시에 점유할 필요가 없어 단순함)

## 5. 컴포넌트 (Spring, 신규)

| 컴포넌트 | 역할 |
|---|---|
| `analysis/AudioAnalysisService` | 파일 검증(포맷/길이/용량) + `AudioDetectionClient` 호출 |
| `analysis/InvalidAudioFileException` | `InvalidImageFileException`과 동일 패턴, `DomainException` 상속 → 400 |
| `detection/AudioDetectionClient` | 포트 인터페이스 |
| `detection/antideepfake/AntiDeepfakeHttpDetectionClient` | 어댑터, `SpaiHttpDetectionClient`와 동일 스타일 (`RestClient` + `MultipartBodyBuilder`) |
| `detection/AiDetectionResult` | 기존 `{model, score}`에 `List<Evidence> evidence` 필드 **추가** — 이미지도 나중에 히트맵 작업 시 이 필드를 채우게 될 공용 타입. 이미지는 당장 빈 리스트 |
| `detection/Evidence` | `{title, description, tags, startSec, endSec}` — record. §3 정정에 따라 시간 구간(temporal)만 지원, 주파수 필드 없음 |
| `api/AnalysisApiDelegateImpl` | `analyzeAudio` 메서드 추가 |
| `api/AnalysisApiMapper` | 음성 응답 매핑 로직 추가 (기존 확장 or 별도 메서드) |

## 6. 데이터 흐름 (성공 케이스)

1. 앱 → `POST /api/v1/analysis/audio` (bearer 인증, multipart, 이미지와 동일 인증 방식)
2. `AudioAnalysisService.validate()` — 빈 파일 / 허용 포맷(wav, mp3, m4a, aac) / 5분 초과 / 25MB 초과 체크
3. `AntiDeepfakeHttpDetectionClient` → Python `/process/audio` 호출
4. Python: subprocess로 추론 스크립트 실행 → `forward_seg`로 프레임별 점수 획득 → JSON 파일로 결과 저장 → 라우터가 읽음
5. `score >= 0.5`일 때만 evidence 생성 — 프레임별 점수 중 상위 3개 구간(연속 프레임을 하나의 구간으로 병합)을 규칙 기반 문장으로 변환. `score < 0.5`면 evidence 빈 배열
6. Spring이 결과를 그대로 매핑해 응답

## 7. 에러 처리

| 상황 | 응답 |
|---|---|
| 빈 파일 / 지원 안 하는 포맷 / 5분·25MB 초과 | 400, `InvalidAudioFileException` |
| Python 탐지 서버 무응답/에러 | 502, `DetectionServiceException` (기존 클래스 재사용) |

**부수 수정**: 이미지 API에 파일 크기 제한이 명시적으로 설정되어 있지 않아 Spring Boot 기본값(1MB)이 적용되는 버그 발견 — 실제 폰 사진(2~10MB)이 업로드 실패할 상태. `spring.servlet.multipart.max-file-size`/`max-request-size`를 명시 설정하며 이미지/음성 모두에 적용되는 값으로 고친다(이미지는 넉넉히, 음성은 25MB 기준).

## 8. 스코프 밖 (의도적으로 제외)

- **사기감지(fraud-risk) reasoning** — 별도 미해결 문제, 이번 스코프 아님 (§1 참고)
- **영상(video) 분석** — 이번 스코프 아님. 영상은 처리 시간이 수초~수분이라 동기 API가 불가능하므로, 추후 `AnalysisJob`(PENDING/PROCESSING/COMPLETED) 비동기 잡 모델이 필요하다 — 이건 원래 초기 아키텍처 설계에 있었다가 이미지 v1 단계에서 sync로 축소되며 미뤄진 것과 동일한 모델을 재사용할 예정. 음성은 처리 속도가 빨라(CPU로도 발화당 ~150ms) 굳이 비동기가 필요 없어 sync로 유지
- **히트맵 연동(이미지)** — 별도로 보류된 작업, `project_veritae_analysis_reasoning_requirement` 메모리에 기록됨. 이번 음성 작업에서 만드는 `Evidence`/`AiDetectionResult` 공용 타입은 나중에 그 작업에서 재사용됨

## 9. 테스트

이미지와 동일한 계층별 테스트 패턴을 따른다:
- `AudioAnalysisServiceTest` (단위, 검증 로직 + 클라이언트 호출)
- `@WebMvcTest AnalysisApiControllerTest`에 음성 케이스 추가 (또는 별도 클래스)
- `AntiDeepfakeHttpDetectionClientTest` (`MockRestServiceServer` 사용, `SpaiHttpDetectionClientTest`와 동일 스타일)

Python 쪽 (`veritae-detection-server`)은 모델 추론을 모킹한 단위 테스트로 시작 (GPU/실제 가중치 없이 CI 가능해야 함 — 기존 `spai_runner` 테스트 패턴 참고).

## 10. 구현/배포 흐름

Claude Code 세션은 이 노트북(veritae-server, veritae-detection-server 로컬 클론 둘 다 접근 가능)에서만 코드를 작성할 수 있고, 3060Ti 데스크탑에는 원격 접근이 불가능하다. 흐름:

1. Spring 쪽 구현 (이 레포)
2. Python 쪽 구현 (veritae-detection-server 로컬 클론)
3. 두 레포 git commit/push
4. 사용자가 데스크탑에서 `git pull` + 새 `antideepfake` conda env 생성(fairseq 특정 커밋 빌드 포함) + AntiDeepfake 저장소 클론 + `mms_300m` 체크포인트 다운로드 — Claude가 명령어 리스트 제공, 사용자가 직접 실행하고 결과를 공유하며 트러블슈팅 (SPAI 셋업 때와 동일한 방식). `detection-api` env는 변경 없음(가벼운 상태 유지)
