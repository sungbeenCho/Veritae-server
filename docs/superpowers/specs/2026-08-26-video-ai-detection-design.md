# 영상 AI 생성(딥페이크) 탐지 기능 — 설계

- **작성일**: 2026-08-26
- **관련 문서**: `C:\Users\sb112\Desktop\deepfake-engine-review.md` (형 작성, 영상/음성 오픈소스 기술검토, 2026-08-14)
- **관련 메모리**: `project_veritae_detection_architecture`, `project_veritae_analysis_reasoning_requirement`, `feedback_no_llm`

## 1. 배경 및 목적

이미지(SPAI)·음성(AntiDeepfake) AI판독은 이미 구현되어 있다. 이번 작업은 세 번째 모달리티인 **영상**을 추가한다.

**스코프 확정 사항 (2026-08-26 세션에서 여러 차례 논의 후 확정):**
- 이번 작업은 **AI판독(생성 여부) 이유만** 다룬다. 사기감지(fraud-risk) 분석은 별도 파이프라인이며 제외한다.
- 영상 탐지는 **(A) 얼굴 조작 딥페이크(face-swap, 립싱크)만** 다룬다. **(B) 완전 생성형 영상(Sora/Veo/Kling류로 통째로 생성된 영상)은 이번 스코프에서 명시적으로 제외**한다 — §8 참고.

## 2. 스코프 결정 과정 (중요 — 왜 이렇게 좁혀졌는지)

**최초 계획(오답, 폐기됨):** 2026-08-12 최초 아키텍처 결정 때 "영상은 ffmpeg로 프레임 뽑아서 이미지와 같은 SPAI 모델을 재사용"으로 대충 적어뒀던 초안이 있었다. 이는 한 번도 검증되지 않은 채 memory에 방치되어 있었고, 2026-08-26 세션 시작 시 이걸 그대로 다음 단계로 넘기려다 문제가 발견됐다(관련 메모리 파일들 수정 완료).

**SPAI 재사용이 틀린 이유:** SPAI는 프레임 하나를 독립적으로 보는 분류기다. 영상 생성 AI(Sora류)가 만든 가짜의 결정적 증거는 프레임 간 **시간적 이상**(그림자 방향, 물리법칙 위반, 미세한 깜빡임)인데, 이건 프레임 단위 정적 분류기로는 원리적으로 볼 수 없다. "영상 AI탐지"가 "이미지 AI탐지"와 별개 연구분야로 존재하는 것 자체가 이 한계의 방증이다.

**(A) vs (B) 오픈소스 실태 조사 (2026-08-26, 형 문서 + 추가 리서치):**
- (A) 얼굴조작: 성숙한 오픈소스 있음 — GenConViT, selimsef/dfdc_deepfake_challenge
- (B) 완전생성형: 지금 당장 갖다 쓸 완성형 오픈소스 없음
  - DeMamba(Apache-2.0): 사전학습 가중치 미공개, 1.25억 파라미터를 200만 개 영상으로 직접 학습해야 함 — 개인 프로젝트 규모에서 비현실적
  - Ivy-xDetector: Qwen2.5-VL-7B 기반 VLM → `feedback_no_llm`에 정면으로 위배, 제외
  - 상용 API(Hive/Reality Defender 등) 병행도 검토했으나 사용자가 최종적으로 제외 결정

**rPPG(생체신호) 검토 후 제외:** 얼굴에 진짜 맥박 신호가 있는지 보는 방식(face-swap·완전생성형 둘 다 이론상 커버 가능)도 검토했으나:
- 완성형 오픈소스 분류기(DeepFakesON-Phys 등)는 전부 LICENSE 파일이 없어 법적으로 사용 불가(라이선스 확인함, `license: null`)
- 직접 구현 시에도, 이 앱이 다룰 실제 콘텐츠(카톡/SNS에서 재다운로드한 여러 번 재압축된 영상)는 rPPG가 필요로 하는 미세한 픽셀 색상 변화가 압축으로 거의 소실되는 최악의 조건이라 신뢰도 낮음
- 검증 안 된 신호를 얼굴조작 탐지 모델의 검증된 점수와 같은 무게로 사용자에게 보여주는 것 자체가 사기예방 제품 특성상 위험(잘못된 확신을 줄 수 있음) → 최종적으로 이번 스코프에서 제외

**결론:** (B)는 이번에 포기한다. 오픈소스가 성숙하거나(사전학습 가중치 공개된 DeMamba급 모델 등장) 자체 학습 여력이 생기면 재검토. 제품에는 "완전생성형 영상은 아직 지원 안 함"을 정직하게 명시할 것 — 형 문서 §4의 "제품에 명시해야 할 한계" 원칙과 일치.

## 3. 선정 모델

**selimsef/dfdc_deepfake_challenge** (2026-08-26, GenConViT에서 최종 변경 — 아래 "변경 이유" 참고)
- 코드+가중치 완전 MIT (GitHub API로 확인)
- `DeepFakeClassifier(encoder="tf_efficientnet_b7_ns")` — **순수 CNN**(EfficientNet-B7, timm 라이브러리). ConvNeXt+Swin 하이브리드인 GenConViT와 달리 Grad-CAM 레퍼런스/사례가 아주 많은 표준적인 조합.
- DFDC(Deepfake Detection Challenge, Meta 주최) 우승작. `predict_folder.py` + Docker, 가중치는 GitHub Releases(~1.9GB)
- 2021년 이후 모델 동결 — 단, §3-1 참고("실전 정확도" 관련 중요 발견)
- GPU 8-12GB 권장(앙상블 전체 기준), 단일 모델로 축소해 부담 낮출 수 있음(3060Ti 8GB 고려 시 단일 모델로 시작 권장)
- **⚠️ 프레임별 점수는 기본적으로 노출되지 않음 (2026-08-26 확인, `kernel_utils.py` 소스 직접 확인)**: `predict_on_video()`가 내부적으로 `torch.sigmoid(y_pred.squeeze())`로 프레임별 예측을 계산하긴 하지만, 곧바로 `confident_strategy`가 `np.mean(preds)`로 평균 내서 **영상당 스칼라 값 하나만 반환**한다. GenConViT와 완전히 동일한 패턴 — AntiDeepfake의 `forward_seg()`처럼 그냥 호출하면 되는 게 아니라, **우리가 평균 내기 전의 프레임별 값을 가로채는 코드를 직접 짜야 함** — §4 참고.

**변경 이유 (GenConViT → selimsef, 2026-08-26):** 처음엔 GenConViT(더 최신, 벤치마크 수치 더 높음)를 골랐으나, self-review 중 Grad-CAM이 GenConViT의 ConvNeXt+Swin 하이브리드 구조에 실제로 적용 가능한지 저장소에 근거가 전혀 없다는 걸 발견. 판독 근거(evidence) 확실성이 이 제품의 핵심 요구사항이라, 순수 CNN이라 Grad-CAM이 훨씬 예측 가능하게 작동하는 selimsef로 최종 변경.

**3-1. 실전 정확도에 대한 중요 발견 (2026-08-26 웹 리서치):** "GenConViT=최신이라 더 정확, selimsef=2021년 동결이라 구식"이라는 구도로 처음 판단했으나, 실제 벤치마크를 찾아보니 이 구도가 부정확했다. GenConViT 저자가 주장한 정확도(학계 벤치마크 DFDC/FF++/Celeb-DF 기준 95.8%/AUC 99.3%)는 2024년 실제 유포 딥페이크로 재평가하는 `Deepfake-Eval-2024`(in-the-wild 벤치마크)에선 **AUC 0.82, 정확도 0.75로 급락**(그것도 파인튜닝 후 수치). 이 벤치마크에 테스트된 오픈소스 SOTA 영상탐지 모델 전반이 **AUC 평균 50% 하락**한다는 결과도 함께 나왔다 — 즉 "최신 모델이라 실전에 더 강하다"는 전제 자체가 이 분야에서 딱히 성립하지 않는다. selimsef와 직접 비교한 자료는 못 찾았으나, 어느 모델을 쓰든 **"학계 벤치마크 수치만큼 실전에서 안 나올 수 있다"는 한계를 제품에 정직하게 명시할 것.**

## 4. Explainability 설계

형 문서 §4 권장 조합(Grad-CAM 히트맵 + 규칙 기반 문장)을 따른다. 신호를 두 종류 계획하는데, **둘 다 모델 자체 계산에서 나오는 값**이라(SPAI 히트맵과 같은 논리) 별도 융합 로직은 필요 없다 — 다만 아래 두 개 다 **§3에서 확인했듯 "기본 제공"이 아니라 우리가 직접 구현해야 하는 부분**이므로, 구현 전 실제 검증(spike)이 필요하다:

1. **공간적 근거 (Grad-CAM)**: `jacobgil/pytorch-grad-cam`(MIT, 활발히 유지보수) 적용 — 가장 의심스러운 프레임에서 모델이 주목한 얼굴 영역을 히트맵으로 오버레이. selimsef는 순수 EfficientNet CNN이라 Grad-CAM 적용 자체는 GenConViT 때보다 훨씬 확실성이 높지만, **이 모델·이 얼굴 크롭 전처리 파이프라인에 실제로 붙여서 말이 되는 히트맵이 나오는지는 여전히 데스크탑에서 스파이크로 확인 필요**(SPAI/AntiDeepfake도 항상 실제 붙여보고 나서야 확정됐던 것과 동일한 원칙). 이미지 SPAI 히트맵과 동일한 `evidenceImage`(base64 PNG) 필드 재사용.
2. **시간적 근거**: §3에서 확인한 대로, `predict_on_video()`를 그대로 안 쓰고 프레임별 sigmoid 예측값을 평균 내기 전에 가로채는 우리 자체 코드를 작성 → 오디오 evidence와 동일한 패턴(연속 구간 병합 → 상위 N개 구간을 규칙 기반 문장으로 변환)으로 "N~M초 구간 의심" 카드 생성. 기존 `Evidence`(`title`, `description`, `tags`, `startSec`, `endSec`) 타입 그대로 재사용. 이쪽은 모델 출력을 가로채기만 하면 되므로 Grad-CAM보다 확실성이 높음.

**최악의 경우(Grad-CAM이 실제로 안 되는 걸로 판명나면):** 공간적 근거 없이 시간적 근거(구간)만으로 §4를 축소하는 것도 fallback으로 고려 — 오디오가 처음부터 시간적 근거만으로 시작했던 것과 같은 수준이 되므로, 완전히 근거 없는 상태(score만)로 떨어지는 건 아님.

## 5. 아키텍처 — 비동기 잡 모델

이미지·음성은 동기(sync)로 구현돼 있지만, 영상은 얼굴검출+GenConViT 추론이 무거워 처리시간을 예측하기 어렵다(오디오도 2.6분 파일이 CPU로 90~105초 걸려 타임아웃을 늘려야 했던 전례가 있는데, 영상은 그보다 무거운 단계가 여러 개 겹침). sync로 갔다가 나중에 뜯어고치는 이중작업 리스크를 피하기 위해 **처음부터 비동기 잡 모델**로 간다. 형 문서 §5도 동일하게 비동기를 권장했다.

```
iOS 앱 → Spring 서버(오케스트레이터) → Python 탐지 서버(3060Ti 데스크탑)
```

- `POST /api/v1/analysis/video` (bearer 인증, multipart) → `AnalysisJob`(PENDING) 생성 + DB 저장 → 즉시 `202 Accepted` + `{jobId}` 반환
- `@Async`로 백그라운드 처리 시작 → 상태 PROCESSING → Python `/process/video` 호출 → 완료 시 COMPLETED(결과 저장) or FAILED(에러 메시지 저장)
- `GET /api/v1/analysis/jobs/{jobId}` (bearer 인증) → 현재 상태 + (완료 시) 결과 반환. 요청자 소유 job인지 확인(다른 사용자 job 조회 차단)
- 이미지/오디오의 기존 sync `DetectionClient`/`AnalysisService`는 전혀 건드리지 않음 — 완전히 별도 컴포넌트로 나란히 추가 (오디오 설계 때부터 계획된 방향)

**DB 저장을 택한 이유(메모리 대신):** 개발 중 IntelliJ로 서버를 자주 재시작하는데, 메모리에만 저장하면 재시작 시 진행 중이던 job이 그냥 사라져 앱이 영영 결과를 못 받는다. 이미 MySQL/JPA가 세팅되어 있고 `Member` 엔티티로 패턴이 검증돼 있어 `AnalysisJob` 엔티티 추가는 작은 작업이다.

## 6. 컴포넌트 (Spring, 신규)

| 컴포넌트 | 역할 |
|---|---|
| `domain/analysisjob/AnalysisJob` | JPA 엔티티: `id`(UUID), `memberId`, `status`(PENDING/PROCESSING/COMPLETED/FAILED), `resultJson`, `errorMessage`, `createdAt`/`updatedAt` |
| `domain/analysisjob/AnalysisJobRepository` | Spring Data JPA 리포지토리 |
| `analysis/VideoAnalysisService` | 파일 검증(포맷/길이/용량) + job 생성 + `@Async` 처리 오케스트레이션 |
| `analysis/InvalidVideoFileException` | 기존 `InvalidImageFileException`/`InvalidAudioFileException`과 동일 패턴 |
| `detection/VideoDetectionClient` | 포트 인터페이스 (이미지/음성과 분리 — ISP, 오디오 설계 때 확정된 원칙) |
| `detection/dfdc/DfdcHttpDetectionClient` | 어댑터, 기존 `SpaiHttpDetectionClient`/`AntiDeepfakeHttpDetectionClient`와 동일 스타일 |
| `api/AnalysisApiDelegateImpl` | `analyzeVideo`, `getAnalysisJob` 메서드 추가 |
| `api/AnalysisApiMapper` | 영상 응답 매핑 로직 추가 |

## 7. 컴포넌트 (Python, veritae-detection-server 신규)

| 컴포넌트 | 역할 |
|---|---|
| `app/routers/video.py` | `POST /process/video` — 기존 `image.py`/`audio.py`와 동일 스타일 |
| `app/services/dfdc_runner.py` | 기존 `spai_runner.py`/`antideepfake_runner.py`와 동일하게, 무거운 의존성은 별도 conda env(`dfdc`)에 격리, subprocess로 커스텀 추론 스크립트 실행 |
| 커스텀 추론 스크립트 | ffmpeg로 얼굴 검출 프레임 샘플링 → selimsef(EfficientNet-B7) 추론(프레임별 점수) → Grad-CAM으로 최고 의심 프레임 히트맵 생성 → 결과 JSON(`score`, `evidence_image`, `evidence[]`) 저장 |

## 8. 데이터 흐름 (성공 케이스)

1. 앱 → `POST /api/v1/analysis/video` → `AnalysisJob` PENDING 생성, `202 Accepted` + jobId
2. `VideoAnalysisService.validate()` — 빈 파일/허용 포맷/용량·길이 제한 체크
3. 비동기로 `DfdcHttpDetectionClient` → Python `/process/video` 호출, job 상태 PROCESSING
4. Python: ffmpeg 얼굴 프레임 샘플링 → selimsef 프레임별 추론 → Grad-CAM 히트맵 → 시간적 evidence 생성 → JSON 반환
5. Spring이 결과를 `AnalysisJob`에 저장, 상태 COMPLETED
6. 앱 → `GET /api/v1/analysis/jobs/{jobId}` 폴링 → 결과 수신

## 9. 에러 처리

| 상황 | 처리 |
|---|---|
| 빈 파일 / 지원 안 하는 포맷 / 용량·길이 초과 | job FAILED, `InvalidVideoFileException` 메시지 저장 (업로드 시점에 즉시 400으로 막을지, job 생성 후 비동기로 FAILED 처리할지는 구현 단계에서 결정) |
| 영상에 얼굴이 검출되지 않음 | job FAILED, "얼굴을 찾을 수 없습니다" 명시적 메시지 (침묵 실패 방지) |
| Python 탐지 서버 무응답/타임아웃 | job FAILED, 기존 `DetectionServiceException` 패턴 재사용 |
| 다른 사용자의 job 조회 시도 | 403 또는 404 (구현 단계에서 결정) |

## 10. 스코프 밖 (의도적으로 제외)

- **(B) 완전 생성형 영상 탐지** — §2 참고. 오픈소스 미성숙, LLM 기반 후보(Ivy-xDetector)는 `feedback_no_llm` 위배, 상용 API는 사용자가 명시적으로 거부. 제품에 한계로 명시할 것.
- **rPPG 생체신호 보강** — §2 참고. 라이선스 있는 완성형 오픈소스 없음, 직접 구현 시에도 이 앱의 실제 콘텐츠(재압축 영상)에 신뢰도가 낮아 제외.
- **SPAI 재사용(어떤 형태로든)** — 프레임 단위 정적 분류기라 영상의 핵심 신호(시간적 이상)를 구조적으로 못 봄. (A)에도 도움 안 됨(face-swap은 얼굴 빼고 전부 진짜 사진이라 SPAI가 반응할 이유 없음).
- **사기감지(fraud-risk) reasoning** — 별도 미해결 문제, 이번 스코프 아님.

## 11. 테스트

이미지/오디오와 동일한 계층별 테스트 패턴을 따른다:
- `VideoAnalysisServiceTest` (단위, 검증 로직 + 비동기 처리 + job 상태 전이)
- `AnalysisJobRepositoryTest` 또는 `@DataJpaTest`로 엔티티 저장/조회 검증
- `@WebMvcTest AnalysisApiControllerTest`에 영상 업로드 + job 조회 케이스 추가
- `DfdcHttpDetectionClientTest` (`MockRestServiceServer`, 기존 클라이언트 테스트와 동일 스타일)

Python 쪽은 모델 추론을 모킹한 단위 테스트로 시작 (GPU/실제 가중치 없이 CI 가능해야 함 — 기존 `spai_runner`/`antideepfake_runner` 테스트 패턴 참고).

## 12. 구현/배포 흐름

기존 이미지/음성 기능과 동일한 흐름:
1. Spring 쪽 구현 (이 레포)
2. Python 쪽 구현 (veritae-detection-server 로컬 클론)
3. 두 레포 git commit/push
4. 사용자가 데스크탑에서 `git pull` + 새 `dfdc` conda env 생성 + selimsef/dfdc_deepfake_challenge 저장소 클론 + `download_weights.sh`로 가중치 다운로드(~1.9GB, GitHub Releases) — Claude가 명령어 리스트 제공, 사용자가 직접 실행하고 결과 공유하며 트러블슈팅 (SPAI/AntiDeepfake 셋업 때와 동일한 방식). `detection-api` env는 변경 없음.

실제 데스크탑 셋업 시, SPAI/AntiDeepfake 때처럼 "리뷰 문서엔 없던 실제 설치 문제"가 나올 가능성이 높음 — 그때 실측해서 문서화할 것.
