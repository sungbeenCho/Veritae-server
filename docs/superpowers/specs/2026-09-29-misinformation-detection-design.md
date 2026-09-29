# 가짜정보(허위정보) 판별 기능 — 설계

- **작성일**: 2026-09-29
- **관련 메모리**: `project_veritae_misinformation_detection`, `feedback_no_llm`, `project_veritae_fraud_model_reinvestigation`, `feedback_never_decide_design_alone`
- **표기 규칙**: 각 결정 옆에 **[확정]**(사용자가 명시적으로 정함) 또는 **[검토 필요]**(설계서를 쓰면서 Claude가 새로 제안함, 사용자 결정 전)를 붙인다. [검토 필요] 항목은 §13에 모아 두었다.

## 1. 목적과 범위

이미지·음성·영상에서 뽑은 문장 중 **한국어 위키백과 내용과 어긋나는 주장**을 찾아, 그 이유와 위키 근거를 함께 보여준다.

- **스코프 = 일반 주장의 사실 검증** [확정]. "이건 금감원 공식 공지다" 같은 사칭성 주장 검증은 제외한다. 대조할 공식 화이트리스트가 없고(경찰청 신고번호, KISA 피싱 URL은 블랙리스트뿐), 목적도 사기감지와 겹치기 때문이다.
- **근거 제시형 보조 도구** [확정]. 판정이 틀릴 수 있다는 전제로, 판정에는 항상 근거 원문과 링크를 함께 보여주고 최종 판단은 사용자가 하게 한다.
- **거짓으로 판정된 문장만 보여준다** [확정]. 참이나 판단불가로 나온 문장은 응답에 넣지 않는다.
- **모든 문장을 검사한다** [확정]. 일부만 검사하는 상한은 두지 않는다.
- **비동기로 빼지 않는다** [확정]. 이미지·음성은 기존 동기 응답에, 영상은 기존 job 결과에 함께 담는다.

**범위 밖**: 사기감지 모델(Lilju) 교체, AntiDeepfake의 GPU 전환, 검증 가치가 있는 문장만 고르는 필터(check-worthiness), 사칭성 주장 검증.

## 2. 왜 이 구조인가 (2026-09-29 실측 요약)

| 확인한 것 | 결과 |
|---|---|
| NLI 분류 모델 4종(36건 세트) | Huffon klue-roberta-base 21/36(인용+부정 0/12, 거짓을 사실로 판정 10건), ys7yoo large 31/36, mDeBERTa 30/36, xlm-roberta-large 32/36 |
| 로컬 LLM qwen3.5:4b | 36건 36/36, 처음 보는 어려운 16건 16/16(mDeBERTa는 13/16, 위험 오답 2건) |
| 위키 검색까지 포함한 전체 흐름(20건) | 16/20, 위험 오답 0. 틀린 4건은 모두 판단불가로 물러남(검색 실패 2, 조각 선택 1, 보수적 추론 1) |
| 공개 위키 검색 API | 몇 번 호출만으로 429(요청 과다) → 동기 응답에 쓸 수 없음 |
| 속도(노트북 CPU) | 판정 한 번에 25~40초, 대부분은 근거를 읽는 시간(prefill). 모델 로드 13초. 데스크탑 GPU 수치는 미측정 |
| 두 단계 판정(라벨만 먼저) | 16%만 빨라지고 판정 4/20이 바뀜(참 문장을 거짓으로 판정 1건) → 기각 |

LLM은 비용 문제로 금지돼 있었으나, 데스크탑에서 로컬로 돌리면 비용이 없어 사용하기로 했다 [확정].

## 3. 전체 흐름

```
[분석 요청] ─┬─ AI 판독(SPAI / AntiDeepfake / dfdc) ────────────────────────┐
             └─ 텍스트 추출+사기감지(scam_infer.py) ── 문장 목록 ── 가짜정보 판정(misinfo_infer.py) ─┤
                                                                                      ↓
                                               {ai_detection, scam_detection, misinformation_detection}
```

- 가짜정보 판정은 사기감지가 **이미 뽑아 둔 문장**을 그대로 받아 쓴다 [확정: 문장분리 재사용]. 텍스트 추출을 두 번 하지 않는다.
- 그래서 가짜정보 판정은 사기감지가 끝난 뒤에 시작한다. AI 판독과는 계속 병렬이다.

문장 하나에 대해 하는 일:
1. Kiwi로 형태소를 분석해 핵심어를 뽑고, 위키 인덱스(SQLite FTS5)에서 BM25로 후보 조각 약 50개를 찾는다.
2. 후보를 e5-small 임베딩으로 문장과 비교해 가장 가까운 5개를 고른다.
3. 문장과 조각 5개를 LLM에 넣어 `지지 / 반박 / 판단불가`와 이유 한 문장을 받는다.
4. `반박`인 문장만 결과에 넣는다.

## 4. 위키 인덱스

- **데이터** [확정]: 매달 나오는 공식 XML 덤프 `kowiki-latest-pages-articles.xml.bz2`(약 1.4GB, 문서 약 77만 개). CirrusSearch 평문 덤프는 2025-12-29 이후 갱신이 멈춰서 쓰지 않는다.
- **변환** [확정]: mwparserfromhell(MIT)로 평문을 만든다. ref, gallery, math 태그와 이미지 링크, 표를 먼저 제거한다(5,000문서 샘플에서 1.6%에 이미지 설명문과 표 찌꺼기가 남았던 문제). 문장 단위로 이어 붙여 약 200자 조각을 만든다.
- **검색** [확정]: SQLite FTS5 파일 하나를 디스크에 둔다. 조각 본문을 Kiwi(Apache-2.0)로 형태소 분석한 결과를 색인하고, 조회도 같은 방식으로 분석한 핵심어로 한다. RAM에 인덱스 전체를 올리지 않는다.
- **구축** [확정: 데스크탑에서 한 번]: `scripts/build_wiki_index.py`가 덤프 → 변환 → 조각 → 색인까지 한 번에 한다. 노트북(순수 파이썬 파서)에서는 변환에만 7시간 이상 걸리는 속도였다. 데스크탑은 C 확장 파서를 쓸 수 있어 더 빠를 것으로 예상하지만 미측정.
- **인덱스 파일 안에 덤프 날짜를 저장**하고, 응답의 `wikiSnapshot`으로 내보낸다.
- **갱신 주기** [검토 필요 A]: 제안은 "사용자가 원할 때 스크립트를 다시 실행"(자동 갱신 없음).

## 5. LLM 판정

- **실행**: 데스크탑에 Ollama를 설치하고 `qwen3.5:4b`를 GPU에서 돌린다 [확정]. Ollama 프로그램은 항상 켜 두고(윈도우 시작 시 자동 실행), **모델은 요청 하나를 처리하는 동안만 올려 두었다가 요청이 끝나면 내린다** [확정]. 호출마다 `keep_alive=0`을 주면 매번 다시 로드되므로, 요청 안에서는 모델을 유지하고 마지막에 한 번만 내린다.
- **프롬프트는 고정한다.** 질문 한 줄만 바꿔도 판정이 바뀌는 것을 실측으로 확인했기 때문이다. 바꿀 때는 반드시 §11의 회귀 테스트를 다시 돌린다. 기준 프롬프트는 2026-09-29 전체 흐름 테스트에서 쓴 것이다:
  > 너는 사실 검증 도우미다. 아래 [근거 문단]들은 위키백과에서 자동으로 찾아온 조각이며, 주장과 관련 없는 조각이 섞여 있을 수 있다. [근거 문단]에 적힌 내용만 보고 [주장]을 판정하라. 네가 원래 알고 있는 지식은 쓰지 마라. (지지/반박/판단불가 정의) … label과 한 문장짜리 reason을 JSON으로 답하라.
- **출력 형식 강제**: Ollama의 구조화 출력(JSON 스키마)으로 `{label: 지지|반박|판단불가, reason}`만 받는다. `think:false`, `temperature:0`.
- **프롬프트 인젝션 대응**: 사용자 콘텐츠는 `[주장]` 칸 안에만 넣고 출력 형식을 스키마로 강제한다. 완전히 막을 수는 없다는 점을 인지하고 쓴다.
- **설정값으로 뺄 것** [확정]: 모델 이름, 근거 조각 수(기본 5), 이유 최대 길이, Ollama 주소와 타임아웃. 데스크탑에서 실측하며 조정한다.

## 6. 기존 기능 보호 — GPU 잠금 [확정]

- detection-server 안에 GPU 잠금을 하나 두고, **GPU를 쓰는 작업은 한 번에 하나만** 돌게 한다. 동시에 돌면 PyTorch 모델(SPAI, dfdc, Whisper)이 GPU 메모리 부족으로 실패하기 때문이다.
- Ollama는 GPU 메모리가 모자라면 오류 없이 CPU와 RAM으로 넘어가 느려진다. 판정할 때마다 Ollama `/api/ps`로 GPU에 올라간 비율을 확인해 100%가 아니면 경고 로그를 남긴다.
- e5-small도 RAM을 아끼기 위해 GPU에서 돌리고 잠금 안에 넣는다 [확정].
- **잠금 단위** [검토 필요 B]: Whisper는 사기감지 서브프로세스(`scam_infer.py`) 안에서 돌기 때문에, 음성·영상은 사기감지 서브프로세스 전체를 잠금 대상으로 잡아야 한다. 그 결과 **영상은 dfdc와 사기감지가 지금처럼 동시에 돌지 못하고 순서대로 돌게 되어 느려진다.** 이미지는 사기감지가 CPU(EasyOCR)만 쓰므로 잠그지 않는다.

## 7. detection-server 변경 (veritae-detection-server)

- `scripts/scam_infer.py`: 결과 JSON에 `sentences`(분리된 문장 전체)를 추가한다. 기존 `score`/`evidence`는 그대로.
- `scripts/misinfo_infer.py` 신규: 문장 목록 JSON을 받아 §3의 1~4를 수행하고 `{wiki_snapshot, claims:[…]}`를 JSON으로 쓴다. 기존 모델들처럼 서브프로세스로 실행한다.
  - **어느 conda 환경에서 돌릴지** [검토 필요 C]: 제안은 이미 torch·transformers가 있는 `text-extraction` 환경에 kiwipiepy를 추가해 재사용하는 것.
- `app/services/misinfo_runner.py` 신규: 서브프로세스 실행, 결과 파싱, 오류를 `MisinfoInferenceError`로 변환.
- `app/services/gpu_lock.py` 신규: GPU 잠금.
- `app/routers/image.py`, `audio.py`, `video.py`: 사기감지 결과의 `sentences`로 가짜정보 판정을 이어서 실행하고, 응답에 `misinformation_detection`을 추가한다. 문장이 하나도 없으면(텍스트 없음) null.
- `app/schemas.py`: `MisinformationDetectionResult`, `MisinformationClaim`, `WikiEvidence` 추가.
- `app/config.py`: §5의 설정값과 인덱스 파일 경로, `misinfo_infer.py` 경로와 타임아웃 추가.
- `scripts/build_wiki_index.py` 신규: §4 구축 스크립트.

## 8. Spring 변경 (veritae-server)

- `detection` 패키지: `MisinformationDetectionResult`, `MisinformationClaim`, `WikiEvidence` 레코드와 공용 파싱 클래스 `MisinformationDetectionJson`(ScamDetectionJson과 같은 방식). `ImageAnalysisResult` / `AudioAnalysisResult` / `VideoAnalysisResult`에 `misinformationDetection` 필드를 추가하고, HTTP 클라이언트 3곳이 `misinformation_detection`을 함께 파싱한다.
- `AnalysisRecord`: `misinfo_refuted_count`(Integer, nullable) 컬럼 추가 [확정: 리포트 집계용]. 기존 기록은 null(검사 안 함)로 두고 백필하지 않는다. `ddl-auto=update`라 별도 마이그레이션은 없다. `completedSync`, `markCompleted`, `markCompletedWithPartialError`가 이 값을 함께 받는다.
- `AnalysisRecordRepository`: 반박 개수가 1 이상인 완료 기록 수를 세는 메서드 추가.
- 리포트 응답에 `misinformationDetectedCount` 추가 [확정].
- `AnalysisApiMapper`, `AnalysisJobView`, `openapi.yaml`, API 명세서 PDF 스크립트(`scripts/generate_api_spec_pdf.py`) 갱신. Swagger 예시는 공유 스키마 예시 버그(`feedback_swagger_shared_schema_example_bug`) 때문에 명시 예시를 넣는다.
- **타임아웃** [검토 필요 D]: 음성은 지금도 90~100초가 걸리는데, 가짜정보 판정이 사기감지 뒤에 붙으므로 Spring의 `detection.read-timeout`(300초)과 detection-server의 타임아웃을 넘길 수 있다. 데스크탑 실측 후 조정하는 것을 제안한다.

## 9. API 명세 [확정]

새 엔드포인트는 없다. 이미지·음성 응답, 영상 job 조회 응답, 기록 목록에 `misinformationDetection`을 `scamDetection` 옆에 추가한다.

```json
"misinformationDetection": {
  "model": "qwen3.5:4b",
  "wikiSnapshot": "2026-09-01",
  "claims": [
    {
      "sentence": "선풍기를 틀고 자면 사망한다.",
      "reason": "근거 문단은 선풍기 사망설을 과학적 근거가 없는 미신이라고 설명합니다.",
      "evidence": [
        {
          "title": "선풍기 사망설",
          "text": "선풍기 사망설이란, 밀폐된 방에서 얼굴을 향해 선풍기를 켜놓은 채로 잠을 자면 사망할 수 있다는 미신이다. …",
          "url": "https://ko.wikipedia.org/wiki/선풍기_사망설"
        }
      ]
    }
  ]
}
```

- **null**: 텍스트가 하나도 추출되지 않음(`scamDetection`과 같은 조건).
- **`claims`가 빈 배열**: 검사했지만 위키에서 반박되는 내용을 찾지 못함. **"모두 사실"이라는 뜻이 아니다.** 명세서 설명에 이 문장을 그대로 적는다.
- **점수 없음**: LLM은 보정된 확률을 주지 않는다.
- `evidence`는 판정에 쓴 근거 조각이다. `title`과 `url`은 위키백과 CC BY-SA 라이선스의 출처 표시 요건 때문에 필수이고, 앱 화면에도 "출처: 위키백과" 표기가 필요하다.
- 리포트 응답에 `misinformationDetectedCount`(반박된 주장이 1개 이상인 완료 기록 수)를 추가한다.

## 10. 오류 처리 [확정]

- 가짜정보 파이프라인이 고장 나면(Ollama가 응답하지 않음, 인덱스 파일을 못 읽음, 서브프로세스 실패, 타임아웃) **분석 요청 전체를 502로 실패시킨다.** 사기감지와 같은 규칙이다. 빈 결과가 "이상 없음"으로 오해되는 것을 막기 위해서다. 클라이언트에는 일반화된 메시지만 주고, 원인은 서버 로그에만 남긴다.
- 문장 하나의 LLM 출력이 형식에 맞지 않으면, 그 문장은 판단불가로 처리하고 경고 로그를 남긴다 [검토 필요 E: 한 문장의 형식 오류로 전체를 실패시키지 않자는 제안].

## 11. 테스트

- **단위 테스트(두 레포)**: 기존처럼 서브프로세스와 HTTP를 목(mock)으로 대신한다. 라우터가 `misinformation_detection`을 붙이는지, 오류 시 502를 내는지, 텍스트가 없으면 null인지, Spring이 파싱·저장·집계를 하는지 확인한다.
- **회귀 테스트셋** [검토 필요 F]: 오늘 만든 36건, 어려운 16건, 위키 검색 포함 20건을 detection-server 레포(`tests/regression/`)에 넣고, 데스크탑에서 실행해 정확도·시간·GPU 메모리를 한 번에 재는 스크립트를 함께 둔다. 이 스크립트는 실제 모델을 쓰므로 일반 테스트(pytest)와 분리한다. 프롬프트나 설정값을 바꿀 때마다 이걸로 확인한다.

## 12. 자원과 남은 위험

- **GPU 메모리(8GB)**: GPU 잠금으로 한 번에 하나만 올라가므로 문제없다고 판단한다. 가장 큰 기존 작업은 Whisper large-v3(약 3~4GB), LLM은 약 4GB.
- **디스크**: 덤프, 변환 텍스트, 인덱스를 합쳐 약 10GB 이하로 예상한다. 구축 후 덤프와 중간 파일은 지울 수 있다. 데스크탑 여유 공간은 미확인.
- **RAM(16GB)**: 사용자 확인 결과 **지금도 분석 중에 꽉 찬다.** 원인은 CPU에서 도는 AntiDeepfake, EasyOCR, Lilju로 추정하지만 측정하지 않았다. 이번 추가분(Kiwi, SQLite 조회)은 1GB 이하로 예상하고, LLM과 e5는 GPU에서 돈다. RAM이 모자라면 실패하지 않고 느려진다. **이 위험을 알고 확정했다.** 구현 후 데스크탑 작업 관리자로 확인한다.
- **속도**: 데스크탑 GPU에서의 문장당 시간, 모델 로드 시간은 미측정이다. §5의 설정값(근거 조각 수, 모델 크기)으로 조정한다.

## 13. 검토 필요 항목 (사용자 결정 전)

| | 항목 | Claude 제안 | 이유 |
|---|---|---|---|
| A | 위키 인덱스 갱신 주기 | 자동 갱신 없이, 원할 때 스크립트 재실행 | 구축에 시간이 걸리고, 위키 변경이 판정에 급하게 반영될 필요는 적음 |
| B | GPU 잠금 단위 | 음성·영상은 사기감지 서브프로세스 전체를 잠금 | Whisper가 그 안에서 돌아서 더 잘게 나눌 수 없음. **대가: 영상 분석이 느려짐** |
| C | misinfo_infer.py 실행 환경 | 기존 `text-extraction` conda 환경에 kiwipiepy 추가 | torch·transformers가 이미 있어 새 환경을 만들 필요가 없음 |
| D | 타임아웃 | 데스크탑 실측 후 조정 | 지금은 근거가 되는 숫자가 없음 |
| E | 문장 하나의 LLM 출력 형식 오류 | 그 문장만 판단불가, 경고 로그 | 드문 형식 오류 하나로 분석 전체를 실패시키지 않기 위함. 파이프라인 고장(§10)과는 구분 |
| F | 회귀 테스트셋 | detection-server 레포 `tests/regression/`에 넣고 pytest와 분리 | 프롬프트·설정을 바꿀 때마다 같은 기준으로 확인해야 해서 |

## 14. 데스크탑 설치 (구현 후 사용자가 실행)

1. Ollama 설치. **윈도우 시작 시 자동 실행되고 트레이에 상주한다**(데스크탑에서는 의도된 동작).
2. `ollama pull qwen3.5:4b`
3. 가짜정보용 파이썬 패키지 설치(§13-C 결정에 따름): kiwipiepy, mwparserfromhell.
4. `build_wiki_index.py` 실행으로 인덱스 구축.
5. 환경변수 설정 후 detection-server 재시작.
6. 회귀 테스트 스크립트로 정확도·시간·GPU 메모리 측정, 필요하면 설정값 조정.

구체적인 명령어는 구현 계획 단계에서 실제 README와 설정을 확인한 뒤 정리한다.
