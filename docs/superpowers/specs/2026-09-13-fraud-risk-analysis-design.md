# 사기 위험도 분석(fraud-risk) 기능 — 설계

- **작성일**: 2026-09-13
- **관련 메모리**: `project_veritae_current_work`, `project_veritae_detection_architecture`, `project_veritae_analysis_reasoning_requirement`, `project_veritae_text_extraction_candidates`, `feedback_no_llm`, `feedback_rule_based_fraud_detection_rejected`

## 1. 배경 및 목적

이미지(SPAI)·음성(AntiDeepfake)·영상(dfdc) AI 생성물 판독은 이미 구현·검증 완료됐다. 이번 작업은 로드맵 2번째 항목인 **사기 위험도 분석**을 추가한다 — 콘텐츠에서 텍스트를 추출해 보이스피싱/사기 패턴을 탐지하는, AI판독과는 완전히 별개인 파이프라인.

**확정된 전제:**
- LLM 사용 금지(`feedback_no_llm`), 룰/키워드 기반 엔진도 사용자가 명시적으로 거절함(`feedback_rule_based_fraud_detection_rejected`) — 대신 기존에 학습된 오픈 모델을 그대로 갖다 쓴다.
- 분류 모델: `Lilju/voicephishing_kobert` (KoBERT 파인튜닝, 이진분류, label 1=피싱). 라이선스 미기재 리스크는 비상업/개인 프로젝트라는 이유로 사용자가 감수하기로 결정함(2026-09-13).
- 텍스트 추출: 이미지는 **PaddleOCR**, 음성/영상(오디오 트랙)은 **faster-whisper**.
- 상세 분석 결과에는 AI판독 이유 + 사기감지 이유가 **둘 다** 반드시 포함되어야 한다 (`project_veritae_analysis_reasoning_requirement`, 반복 강조된 하드 요구사항).

## 2. API 응답 구조

**결정: 기존 이미지/음성/영상 분석 엔드포인트 응답에 필드를 추가한다.** 완전히 별도의 엔드포인트(예: `POST /analysis/scam-text`)는 채택하지 않는다.

- `POST /api/v1/analysis/image`, `POST /api/v1/analysis/audio`, `GET /api/v1/analysis/jobs/{jobId}`(영상)의 응답에 기존 `aiDetection`과 나란히 `scamDetection` 필드를 추가한다.
- 텍스트가 전혀 추출되지 않으면(글자 없는 사진, 무음 등) `scamDetection` 필드는 생략(null).
- **이유:** 이 제품의 "상세 분석" 화면은 원래 AI판독 근거 + 위험도 분석 두 탭이 한 결과 안에 같이 있는 구조로 설계되어 있어, 클라이언트가 파일 하나 업로드해서 한 번의 요청/응답으로 둘 다 받는 게 자연스럽다. 완전 별도 엔드포인트는 iOS 쪽이 매 분석마다 서버 호출을 두 번 해야 하고 계약도 새로 합의해야 해서 채택하지 않았다.

### 2-1. 응답시간 리스크와 병렬 실행

이미지/음성은 동기 응답이라, `scamDetection` 계산(OCR/STT+Lilju)을 AI판독 뒤에 순차로 붙이면 응답시간이 늘어난다. 특히 음성은 이미 AntiDeepfake 자체가 2.6분 파일 기준 89~106초가 걸려 `read-timeout`을 300초로 늘려둔 상태라, STT까지 순차로 얹으면 타임아웃 재발 위험이 있다.

**결정:** 각 엔드포인트에서 AI판독 모델 호출과 텍스트추출+사기감지 파이프라인 호출을 **병렬로 실행**한다(서로 의존관계 없는 독립 연산이므로). Spring 서비스 레이어에서 `CompletableFuture` 등으로 동시에 실행 후 join. 이렇게 하면 전체 응답시간이 두 소요시간의 합이 아니라 더 느린 쪽 기준이 된다.

영상은 이미 비동기(job+폴링) 구조라 이 문제 자체가 없다 — 그대로 job 처리 로직 안에 사기감지 단계를 추가하면 된다.

**미검증 리스크:** 병렬화 후에도 음성이 실사용 파일 길이(최대 5분)에서 여전히 너무 느리면, 그때 음성도 영상처럼 비동기(job+폴링) 모델로 전환을 재논의한다 — 이번 스코프에서 미리 하지 않는다.

## 3. 텍스트 추출 + 문장 분리

각 포맷에서 원시 텍스트를 얻는 방법은 다르지만, 이후 파이프라인(문장 분리 → 사기감지 → 집계)은 포맷 무관 공통 로직이다.

- **이미지**: PaddleOCR 실행 → 인식된 텍스트 줄(line) 단위 결과.
- **음성/영상**: faster-whisper 실행 → 발화 간 쉬는 구간 기준 segment 단위 결과(타임스탬프 포함).

**문장 분리 (2단계, 조건 분기 없이 균일하게 적용):**
1. 위에서 얻은 native 단위(OCR 줄 / STT segment)를 1차 단위로 삼는다 — 특히 음성은 여기서 시간 구간 정보를 얻는다.
2. 각 native 단위 텍스트를 예외 없이 `kss`(Korean Sentence Splitter, MIT, 규칙 기반, 비-LLM)에 한 번씩 통과시킨다. 쪼갤 게 없으면 원래 그대로 1개, 있으면 여러 개로 나뉘어 나온다 — "길면 쪼갠다" 식의 조건 분기가 필요 없다.
3. `kss` 호출은 try/except로 감싸고, 실패 시 해당 native 단위를 그대로 사용(폴백) — 히트맵 생성 실패 시 null 반환하는 기존 패턴과 동일한 안전장치.

**미검증 리스크 (구현 시 실제 데스크톱에서 확인 필요, 이 프로젝트의 모든 모델 붙이기 작업이 겪은 것과 같은 패턴):**
- PaddleOCR/faster-whisper/Lilju/kss가 새 conda env에서 함께 깨끗이 설치되는지.
- `kss`가 STT/OCR의 실제 노이즈 섞인 출력(구두점 없는 구어체, OCR 인식 오류)에서 기대대로 동작하는지 — 위 폴백으로 완화하되 실측 필요.
- 병렬화로 음성 응답시간이 실제로 허용 범위 안에 들어오는지.

## 4. 사기감지 분류 + 위험도 집계

- 3에서 나온 각 최종 문장 단위를 `Lilju/voicephishing_kobert`에 개별적으로 넣어 피싱 확률을 하나씩 얻는다.
- **최종 위험도 점수 = 최고확률×0.7 + 평균확률×0.3** (Lilju 자체 데모 `app.py`의 집계 방식을 그대로 채택, 사용자 확정 2026-09-13). 이 계산식은 모델의 일부가 아니라 후처리 로직이라 자유롭게 채택/변경 가능함을 확인하고 결정함.
- **보안:** 로딩 시 `.safetensors`만 사용(pickle 역직렬화 회피), `trust_remote_code` 미사용(표준 `BertForSequenceClassification`).

## 5. "판독 이유" — 사기감지 evidence 카드

- 개별 문장 확률이 **0.5 이상**인 문장만 evidence로 포함한다 — 기존 이미지(`evidenceImage`)/음성·영상(`evidence`)이 이미 쓰고 있는 0.5 임계값 패턴과 동일하게 맞춤.
- evidence 카드는 **문장 원문 + 그 문장의 개별 확률**만 담는다. "기관사칭", "긴박함유도" 같은 **사기 유형 라벨은 포함하지 않는다** — 그런 라벨을 생성하려면 결국 키워드/규칙 기반 분류가 필요한데, 이는 이미 사용자가 명시적으로 거절한 방향(`feedback_rule_based_fraud_detection_rejected`)이라 다시 시도하지 않는다. 모델이 위험하다고 판단한 문장 원문 자체가 사용자에게 충분한 설득력 있는 근거가 된다고 보고 채택.

## 6. 데스크톱 Python 서버(veritae-detection-server) 구조

기존 SPAI/AntiDeepfake/dfdc와 동일한 패턴을 따른다 — 가벼운 FastAPI 서버 프로세스(`detection-api` conda env)는 하나로 유지하고, 무거운 ML 의존성만 별도 conda env로 분리해 subprocess로 호출한다.

- 기존 `POST /process/image`, `/process/audio`, `/process/video` 각각을, 기존 AI판독 모델 호출과 **병렬로** 텍스트추출+사기감지 파이프라인(3~5절)도 실행하도록 확장한다. 응답에 `ai_detection`과 나란히 `scam_detection` 필드 추가.
- **새 conda env**(가칭 `text-extraction`)를 만들어 PaddleOCR + faster-whisper + Lilju(transformers) + kss 의존성을 여기 격리한다 — 기존 `spai`/`antideepfake`/`dfdc` env들과 패키지 버전 충돌 방지 목적, 이 프로젝트의 기존 원칙과 동일.
- 이미지/음성/영상 라우터가 공통으로 쓰는 "문장분리 → Lilju 채점 → 집계 → evidence 필터링" 로직은 포맷 무관 공유 모듈로 구현한다(포맷별로는 OCR이냐 STT냐만 다름).

## 7. Spring 쪽 구조

기존 `DetectionClient` 패턴을 재사용한다 — `FraudDetectionClient`(가칭) 인터페이스를 새로 만들고, 데스크톱의 확장된 `/process/image`, `/process/audio`, `/process/video` 응답에서 `scam_detection` 필드를 파싱하는 구현체를 둔다. 나중에 모델 교체가 필요해져도(라이선스 문제 등) 호출부(서비스/컨트롤러) 코드는 안 건드리고 구현체만 교체 가능.

`ImageDetectionResult`/`AudioDetectionResult`/`VideoDetectionResult` 각각에 `ScamDetectionResult(model, score, evidence)` 타입의 nullable 필드를 추가한다(정확한 필드명은 구현 단계에서 기존 네이밍 컨벤션에 맞춰 확정).

## 8. 아직 안 정해진 것

- Spring/OpenAPI 스키마의 정확한 필드명(`scamDetection` vs 다른 이름), `ScamDetectionResult`의 정확한 필드 구성 — 구현 계획(writing-plans) 단계에서 기존 컨벤션에 맞춰 확정.
- 텍스트 추출~사기감지 파이프라인의 정확한 에러 처리(OCR/STT 자체가 실패했을 때 전체 요청을 실패시킬지, 그냥 `scamDetection`만 null로 둘지) — AI판독은 그대로 성공시키고 사기감지만 조용히 생략하는 쪽이 기존 "best-effort, null on failure" 패턴과 일관되어 유력하나, 구현 단계에서 명시적으로 확정.
