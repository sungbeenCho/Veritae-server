# 사기 위험도 분석(fraud-risk) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 이미지/음성/영상 분석 응답에 AI판독(`aiDetection`)과 나란히 사기 위험도 판독(`scamDetection`) 필드를 추가한다 — OCR/STT로 텍스트를 뽑아 `Lilju/voicephishing_kobert`로 문장별 피싱 확률을 매기고, 하나의 위험도 점수 + 근거 문장 목록을 반환한다.

**Architecture:** 데스크톱 Python 서버(`veritae-detection-server`)의 기존 `/process/image`, `/process/audio`, `/process/video`가 각각 기존 AI판독 모델 호출과 새 텍스트추출+사기감지 파이프라인 호출을 **내부적으로 병렬 실행**(`asyncio.gather`)해서 한 응답에 `ai_detection`+`scam_detection`을 같이 담아 반환한다. Spring(`veritae-server`)의 `DetectionClient`/`AudioDetectionClient`/`VideoDetectionClient`는 지금처럼 엔드포인트당 HTTP 호출 한 번만 하고, 그 응답에서 두 필드를 같이 파싱해 새 조합 타입(`ImageAnalysisResult` 등)으로 반환한다.

**Tech Stack:** PaddleOCR(OCR), faster-whisper(STT), `kss`(한국어 문장분리), `Lilju/voicephishing_kobert`(HuggingFace transformers, KoBERT 파인튜닝) — 전부 새 conda env `text-extraction`에 격리. Spring Boot 4 / Jackson 3 / openapi-generator(기존 스택 그대로).

**Spec:** `docs/superpowers/specs/2026-09-13-fraud-risk-analysis-design.md`

## Global Constraints

- LLM 사용 금지, 룰/키워드 기반 사기 유형 분류(라벨) 금지 — evidence 카드는 문장 원문+점수만 담는다(spec §5).
- 사기감지 evidence는 개별 문장 확률 **0.5 이상**만 포함한다(spec §5, 기존 evidenceImage/evidence 0.5 임계값과 동일).
- 최종 위험도 점수 = `최고확률×0.7 + 평균확률×0.3`(spec §4).
- 텍스트가 전혀 추출되지 않으면 `scamDetection`/`scam_detection` 필드 자체가 없다(null) — 응답 최상위에서 `aiDetection`과 형제 필드다(중첩 금지, spec §2/§7).
- 사기감지 파이프라인 실패(OCR/STT/kss/Lilju subprocess 에러)는 AI판독이 성공했다면 전체 요청을 실패시키지 않는다 — `scamDetection: null`로 처리하는 best-effort(기존 `evidenceImage`/Grad-CAM과 동일 원칙, 이번 구현 단계에서 확정).
- Python 서브프로세스 기반 구현(SPAI/AntiDeepfake/dfdc와 동일 패턴): 무거운 ML 의존성은 `text-extraction` conda env에 격리, FastAPI 프로세스(`detection-api` env)는 subprocess로만 호출.
- `.safetensors`만 사용(pickle 역직렬화 회피), `trust_remote_code` 미사용(spec §4).

---

## Python 쪽 (veritae-detection-server)

### Task 1: `scam_runner.py` — subprocess 호출 래퍼 + config 설정

**Files:**
- Modify: `app/config.py` (Settings 클래스에 text-extraction 설정 추가)
- Create: `app/services/scam_runner.py`
- Test: `tests/test_scam_runner.py`

**Interfaces:**
- Produces: `class ScamInferenceError(RuntimeError)`, `class ScamResult: score: float | None; evidence: list[dict]`, `run_scam_inference_image(image_bytes: bytes, filename: str) -> ScamResult`, `run_scam_inference_audio(audio_bytes: bytes, filename: str) -> ScamResult`, `run_scam_inference_video(video_bytes: bytes, filename: str) -> ScamResult`
- Consumes: `app.config.get_settings()`(기존)

- [ ] **Step 1: `app/config.py`에 설정 추가**

`Settings.__init__` 마지막(dfdc 설정 블록 뒤)에 추가:

```python
        # --- 사기 위험도 분석(fraud-risk) 설정. OCR(PaddleOCR)/STT(faster-whisper)/
        # 문장분리(kss)/사기감지(Lilju/voicephishing_kobert)를 전부 별도 conda env
        # (text-extraction)에 격리한다. GitHub repo clone이 필요 없는 pip 패키지들이라
        # SPAI/AntiDeepfake/dfdc와 달리 REPO_DIR 필수 설정은 없다.
        self.text_extraction_python = os.environ.get("TEXT_EXTRACTION_PYTHON", "python")
        self.text_extraction_script = Path(
            os.environ.get(
                "TEXT_EXTRACTION_SCRIPT",
                str(Path(__file__).resolve().parent.parent / "scripts" / "scam_infer.py"),
            )
        )
        self.lilju_model_id = os.environ.get("LILJU_MODEL_ID", "Lilju/voicephishing_kobert")
        self.paddleocr_lang = os.environ.get("PADDLEOCR_LANG", "korean")
        self.whisper_model_size = os.environ.get("WHISPER_MODEL_SIZE", "large-v3")
        # 실측 없음(2026-09-13 기준) - STT(faster-whisper)가 5분 길이 오디오에서 얼마나
        # 걸릴지 알 수 없어 넉넉히 잡음. 데스크탑 실측 후 조정 필요(SPAI/AntiDeepfake/dfdc와 동일 패턴).
        self.text_extraction_timeout_seconds = int(os.environ.get("TEXT_EXTRACTION_TIMEOUT_SECONDS", "300"))
        self.text_extraction_work_dir = Path(os.environ.get("TEXT_EXTRACTION_WORK_DIR", "./tmp")).resolve()
        self.text_extraction_work_dir.mkdir(parents=True, exist_ok=True)
```

- [ ] **Step 2: 실패하는 테스트 작성 — `tests/test_scam_runner.py`**

```python
import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from app.services.scam_runner import (
    ScamInferenceError,
    _safe_filename,
    run_scam_inference_audio,
    run_scam_inference_image,
    run_scam_inference_video,
)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("photo.jpg", "photo.jpg"),
        ("../../etc/passwd", "passwd"),
        ("..\\..\\Windows\\System32\\evil.dll", "evil.dll"),
        ("..", "upload"),
        ("", "upload"),
    ],
)
def test_safe_filename_strips_path_traversal(raw, expected):
    assert _safe_filename(raw) == expected


def _scam_settings(tmp_path) -> MagicMock:
    settings = MagicMock()
    settings.text_extraction_python = "python"
    settings.text_extraction_script = Path("/fake/scam_infer.py")
    settings.text_extraction_timeout_seconds = 300
    settings.text_extraction_work_dir = tmp_path
    settings.lilju_model_id = "Lilju/voicephishing_kobert"
    settings.paddleocr_lang = "korean"
    settings.whisper_model_size = "large-v3"
    return settings


def _write_result_json(output_file: Path, score, evidence=None) -> None:
    output_file.write_text(json.dumps({"score": score, "evidence": evidence or []}), encoding="utf-8")


@patch("app.services.scam_runner.subprocess.run")
@patch("app.services.scam_runner.get_settings")
def test_run_scam_inference_image_returns_score_and_evidence(mock_get_settings, mock_run, tmp_path):
    mock_get_settings.return_value = _scam_settings(tmp_path)

    def fake_run(command, **kwargs):
        output_file = Path(command[command.index("--output") + 1])
        _write_result_json(output_file, 0.82, [{"sentence": "계좌번호를 알려주세요", "score": 0.95}])
        return MagicMock(returncode=0, stderr="")

    mock_run.side_effect = fake_run

    result = run_scam_inference_image(b"fake-image-bytes", "test.jpg")

    assert result.score == 0.82
    assert result.evidence == [{"sentence": "계좌번호를 알려주세요", "score": 0.95}]
    called_command = mock_run.call_args.args[0]
    assert called_command[called_command.index("--mode") + 1] == "ocr"


@patch("app.services.scam_runner.subprocess.run")
@patch("app.services.scam_runner.get_settings")
def test_run_scam_inference_audio_uses_stt_mode_and_returns_none_score_when_no_text(mock_get_settings, mock_run, tmp_path):
    mock_get_settings.return_value = _scam_settings(tmp_path)

    def fake_run(command, **kwargs):
        output_file = Path(command[command.index("--output") + 1])
        _write_result_json(output_file, None)
        return MagicMock(returncode=0, stderr="")

    mock_run.side_effect = fake_run

    result = run_scam_inference_audio(b"fake-audio-bytes", "test.wav")

    assert result.score is None
    assert result.evidence == []
    called_command = mock_run.call_args.args[0]
    assert called_command[called_command.index("--mode") + 1] == "stt"


@patch("app.services.scam_runner.subprocess.run")
@patch("app.services.scam_runner.get_settings")
def test_run_scam_inference_raises_on_nonzero_exit(mock_get_settings, mock_run, tmp_path):
    mock_get_settings.return_value = _scam_settings(tmp_path)
    mock_run.return_value = MagicMock(returncode=1, stderr="boom")

    with pytest.raises(ScamInferenceError):
        run_scam_inference_image(b"fake-image-bytes", "test.jpg")


@patch("app.services.scam_runner.subprocess.run")
@patch("app.services.scam_runner.get_settings")
def test_run_scam_inference_raises_on_timeout(mock_get_settings, mock_run, tmp_path):
    import subprocess

    mock_get_settings.return_value = _scam_settings(tmp_path)
    mock_run.side_effect = subprocess.TimeoutExpired(cmd="scam_infer.py", timeout=300)

    with pytest.raises(ScamInferenceError):
        run_scam_inference_image(b"fake-image-bytes", "test.jpg")


@patch("app.services.scam_runner.subprocess.run")
@patch("app.services.scam_runner.get_settings")
def test_run_scam_inference_video_extracts_audio_then_runs_stt(mock_get_settings, mock_run, tmp_path):
    mock_get_settings.return_value = _scam_settings(tmp_path)

    def fake_run(command, **kwargs):
        if command[0] == "ffmpeg":
            audio_path = Path(command[-1])
            audio_path.write_bytes(b"fake-wav-bytes")
            return MagicMock(returncode=0, stderr="")
        output_file = Path(command[command.index("--output") + 1])
        _write_result_json(output_file, 0.6, [{"sentence": "지금 바로 이체하세요", "score": 0.9}])
        return MagicMock(returncode=0, stderr="")

    mock_run.side_effect = fake_run

    result = run_scam_inference_video(b"fake-video-bytes", "test.mp4")

    assert result.score == 0.6
    assert mock_run.call_count == 2
    first_command = mock_run.call_args_list[0].args[0]
    assert first_command[0] == "ffmpeg"
    second_command = mock_run.call_args_list[1].args[0]
    assert second_command[second_command.index("--mode") + 1] == "stt"


@patch("app.services.scam_runner.subprocess.run")
@patch("app.services.scam_runner.get_settings")
def test_run_scam_inference_video_raises_when_ffmpeg_fails(mock_get_settings, mock_run, tmp_path):
    mock_get_settings.return_value = _scam_settings(tmp_path)
    mock_run.return_value = MagicMock(returncode=1, stderr="ffmpeg boom")

    with pytest.raises(ScamInferenceError):
        run_scam_inference_video(b"fake-video-bytes", "test.mp4")
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `python -m pytest tests/test_scam_runner.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.services.scam_runner'`

- [ ] **Step 4: `app/services/scam_runner.py` 구현**

```python
import json
import shutil
import subprocess
import uuid
from pathlib import Path, PureWindowsPath

from app.config import get_settings


class ScamInferenceError(RuntimeError):
    pass


class ScamResult:
    def __init__(self, score: float | None, evidence: list[dict]):
        self.score = score
        self.evidence = evidence


def _safe_filename(filename: str) -> str:
    # PureWindowsPath treats both / and \ as separators, so this strips any
    # directory components regardless of host OS (spai_runner.py 등과 동일 로직).
    name = PureWindowsPath(filename).name
    return name if name and name not in (".", "..") else "upload"


def _run_scam_infer(mode: str, input_bytes: bytes, filename: str) -> ScamResult:
    settings = get_settings()
    job_dir = settings.text_extraction_work_dir / uuid.uuid4().hex
    job_dir.mkdir(parents=True, exist_ok=True)
    input_file = job_dir / _safe_filename(filename)
    output_file = job_dir / "result.json"
    input_file.write_bytes(input_bytes)

    command = [
        settings.text_extraction_python,
        str(settings.text_extraction_script),
        "--mode", mode,
        "--input", str(input_file),
        "--output", str(output_file),
        "--lilju-model-id", settings.lilju_model_id,
        "--paddleocr-lang", settings.paddleocr_lang,
        "--whisper-model-size", settings.whisper_model_size,
    ]

    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=settings.text_extraction_timeout_seconds,
        )

        if result.returncode != 0:
            raise ScamInferenceError(f"사기감지 추론 실패: {result.stderr[-2000:]}")

        if not output_file.exists():
            raise ScamInferenceError(f"expected output JSON not found: {output_file}")

        return _parse_result(output_file)
    except subprocess.TimeoutExpired as e:
        raise ScamInferenceError(
            f"사기감지 추론이 {settings.text_extraction_timeout_seconds}초 안에 끝나지 않았습니다"
        ) from e
    finally:
        shutil.rmtree(job_dir, ignore_errors=True)


def _parse_result(output_file: Path) -> ScamResult:
    try:
        data = json.loads(output_file.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError, OSError) as e:
        raise ScamInferenceError(f"사기감지 output JSON을 읽거나 파싱할 수 없습니다: {output_file}") from e
    return ScamResult(score=data.get("score"), evidence=data.get("evidence", []))


def run_scam_inference_image(image_bytes: bytes, filename: str) -> ScamResult:
    return _run_scam_infer("ocr", image_bytes, filename)


def run_scam_inference_audio(audio_bytes: bytes, filename: str) -> ScamResult:
    return _run_scam_infer("stt", audio_bytes, filename)


def run_scam_inference_video(video_bytes: bytes, filename: str) -> ScamResult:
    """faster-whisper는 오디오 입력만 받으므로, 영상에서 오디오 트랙만 ffmpeg로 뽑아낸
    뒤 STT 모드로 넘긴다(antideepfake_infer.py의 압축포맷→wav 변환과 동일한 ffmpeg
    서브프로세스 패턴)."""
    settings = get_settings()
    job_dir = settings.text_extraction_work_dir / uuid.uuid4().hex
    job_dir.mkdir(parents=True, exist_ok=True)
    video_file = job_dir / _safe_filename(filename)
    audio_file = job_dir / "audio.wav"
    video_file.write_bytes(video_bytes)

    try:
        ffmpeg_result = subprocess.run(
            ["ffmpeg", "-y", "-i", str(video_file), "-vn", "-acodec", "pcm_s16le", str(audio_file)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=settings.text_extraction_timeout_seconds,
        )
        if ffmpeg_result.returncode != 0:
            raise ScamInferenceError(f"영상에서 오디오 트랙 추출 실패: {ffmpeg_result.stderr[-2000:]}")

        return _run_scam_infer("stt", audio_file.read_bytes(), "audio.wav")
    finally:
        shutil.rmtree(job_dir, ignore_errors=True)
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `python -m pytest tests/test_scam_runner.py -v`
Expected: PASS (8개 테스트)

- [ ] **Step 6: 커밋**

```bash
git add app/config.py app/services/scam_runner.py tests/test_scam_runner.py
git commit -m "feat(fraud-risk): 사기감지 subprocess 래퍼(scam_runner) 추가"
```

---

### Task 2: `scripts/scam_infer.py` — OCR/STT+kss+Lilju 실행 스크립트

**Files:**
- Create: `scripts/scam_infer.py`

**Interfaces:**
- Consumes: Task 1의 `scam_runner.py`가 subprocess로 이 스크립트를 호출(`--mode`, `--input`, `--output`, `--lilju-model-id`, `--paddleocr-lang`, `--whisper-model-size`)
- Produces: `--output` 경로에 `{"score": float|null, "evidence": [{"sentence": str, "score": float}, ...]}` JSON

이 스크립트는 `text-extraction` conda env(PaddleOCR/faster-whisper/transformers/kss 설치)에서만 실행 가능해, 이 리포의 로컬 개발 환경에서는 임포트 자체가 실패한다 — `scripts/antideepfake_infer.py`/`scripts/dfdc_infer.py`와 동일하게 **자동화된 테스트를 두지 않는다**(기존 프로젝트 관례, 실제 검증은 데스크톱에서 실행해서 확인).

- [ ] **Step 1: 스크립트 작성**

```python
#!/usr/bin/env python
"""scam_infer.py

이미지(OCR) 또는 음성(STT)에서 텍스트를 추출해 Lilju/voicephishing_kobert로 문장별
피싱 확률을 매기고, 하나의 위험도 점수 + 근거 문장 목록을 JSON으로 저장한다.

`text-extraction` conda env(PaddleOCR, faster-whisper, transformers, kss 설치됨)에서
실행되어야 한다. veritae-detection-server(FastAPI, detection-api env)는 이 스크립트를
subprocess로 호출하고 --output 경로의 JSON만 읽는다 - 무거운 의존성을 FastAPI
프로세스에 넣지 않기 위함(SPAI/AntiDeepfake/dfdc 연동과 동일한 패턴).

docs(veritae-server 레포): docs/superpowers/specs/2026-09-13-fraud-risk-analysis-design.md
"""
import argparse
import json
from pathlib import Path

EVIDENCE_SCORE_THRESHOLD = 0.5  # spai_runner.py/antideepfake_infer.py/dfdc_infer.py와 동일 임계값
MAX_SENTENCE_LENGTH = 300  # Lilju 학습 시 입력 길이를 크게 벗어나는 극단값 방어용 - 실측 근거 없는 안전장치
PHISHING_LABEL_INDEX = 1  # Lilju 모델카드 없음 - 2026-09-13 실측(9/10 정확도)으로 확인된 값


def extract_text_units_ocr(image_path: Path, lang: str) -> list[str]:
    from paddleocr import PaddleOCR

    ocr = PaddleOCR(use_angle_cls=True, lang=lang)
    result = ocr.ocr(str(image_path), cls=True)
    if not result or not result[0]:
        return []
    return [line[1][0] for line in result[0]]


def extract_text_units_stt(audio_path: Path, model_size: str) -> list[str]:
    from faster_whisper import WhisperModel

    model = WhisperModel(model_size)
    segments, _info = model.transcribe(str(audio_path), language="ko")
    return [segment.text.strip() for segment in segments if segment.text.strip()]


def split_sentences(text_units: list[str]) -> list[str]:
    """native 단위(OCR 줄/STT segment) 각각을 kss로 한 번씩 통과시킨다. 쪼갤 게 없으면
    원래 그대로 1개가 나오므로 "길면 쪼갠다"는 조건 분기가 필요 없다. kss는 문어체를
    가정하고 만들어져 STT/OCR의 노이즈 낀 실제 출력에서 어떻게 반응할지 데스크탑
    실측 전이므로, 실패 시 원문 그대로 폴백한다(2026-09-13 설계)."""
    import kss

    sentences: list[str] = []
    for unit in text_units:
        if not unit:
            continue
        try:
            sentences.extend(s for s in kss.split_sentences(unit) if s.strip())
        except Exception:
            sentences.append(unit)
    return [s[:MAX_SENTENCE_LENGTH] for s in sentences]


def score_sentences(sentences: list[str], model_id: str) -> list[float]:
    import torch
    from transformers import AutoModelForSequenceClassification, AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(model_id)
    model = AutoModelForSequenceClassification.from_pretrained(model_id, use_safetensors=True)
    model.eval()

    scores = []
    with torch.no_grad():
        for sentence in sentences:
            inputs = tokenizer(sentence, return_tensors="pt", truncation=True)
            logits = model(**inputs).logits
            probs = torch.softmax(logits, dim=-1)
            scores.append(probs[0, PHISHING_LABEL_INDEX].item())
    return scores


def aggregate(scores: list[float]) -> float:
    """사용자 확정(2026-09-13): 최고확률x0.7 + 평균확률x0.3 (Lilju 자체 데모 app.py의
    집계 방식을 그대로 채택 - 모델의 일부가 아닌 후처리 로직이라 자유롭게 채택 가능함을
    확인하고 결정함, spec §4)."""
    return max(scores) * 0.7 + (sum(scores) / len(scores)) * 0.3


def build_evidence(sentences: list[str], scores: list[float]) -> list[dict]:
    return [
        {"sentence": sentence, "score": score}
        for sentence, score in zip(sentences, scores)
        if score >= EVIDENCE_SCORE_THRESHOLD
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", required=True, choices=["ocr", "stt"])
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--lilju-model-id", required=True)
    parser.add_argument("--paddleocr-lang", required=True)
    parser.add_argument("--whisper-model-size", required=True)
    args = parser.parse_args()

    if args.mode == "ocr":
        text_units = extract_text_units_ocr(args.input, args.paddleocr_lang)
    else:
        text_units = extract_text_units_stt(args.input, args.whisper_model_size)

    sentences = split_sentences(text_units)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    if not sentences:
        args.output.write_text(json.dumps({"score": None, "evidence": []}), encoding="utf-8")
        return

    scores = score_sentences(sentences, args.lilju_model_id)
    result = {"score": aggregate(scores), "evidence": build_evidence(sentences, scores)}
    args.output.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 커밋**

```bash
git add scripts/scam_infer.py
git commit -m "feat(fraud-risk): OCR/STT+kss+Lilju 추론 스크립트(scam_infer.py) 추가"
```

---

### Task 3: 이미지 라우터 — `scam_detection` 병렬 실행 + 응답 필드 추가

**Files:**
- Modify: `app/schemas.py`
- Modify: `app/routers/image.py`
- Modify: `tests/test_image_router.py`

**Interfaces:**
- Consumes: Task 1의 `run_scam_inference_image`, `ScamResult`, `ScamInferenceError`
- Produces: `ScamEvidence(BaseModel)`, `ScamDetectionResult(BaseModel)` (다른 라우터도 재사용), `ImageAnalysisResponse.scam_detection: ScamDetectionResult | None`

- [ ] **Step 1: `app/schemas.py`에 타입 추가**

파일 최상단(`AIDetectionResult` 정의 앞)에 추가:

```python
class ScamEvidence(BaseModel):
    sentence: str
    score: float


class ScamDetectionResult(BaseModel):
    model: str
    score: float
    evidence: list[ScamEvidence]
```

`ImageAnalysisResponse`를 다음으로 교체:

```python
class ImageAnalysisResponse(BaseModel):
    ai_detection: AIDetectionResult
    scam_detection: ScamDetectionResult | None = None
```

- [ ] **Step 2: 실패하는 테스트 작성 — `tests/test_image_router.py`에 추가**

파일 상단 import에 추가:

```python
from app.services.scam_runner import ScamInferenceError, ScamResult
```

기존 3개 테스트(`test_process_image_returns_score`, `test_process_image_returns_evidence_image_when_present`, `test_process_image_returns_502_on_spai_failure`)는 이제 `run_scam_inference_image`도 호출되므로 각각에 다음 한 줄을 추가:

```python
    monkeypatch.setattr(
        image_router, "run_scam_inference_image", lambda data, filename: ScamResult(None, [])
    )
```

파일 끝에 새 테스트 3개 추가:

```python
def test_process_image_returns_scam_detection_when_present(monkeypatch):
    monkeypatch.setattr(
        image_router, "run_spai_inference", lambda data, filename: SpaiResult(0.87, None)
    )
    monkeypatch.setattr(
        image_router,
        "run_scam_inference_image",
        lambda data, filename: ScamResult(0.82, [{"sentence": "계좌번호를 알려주세요", "score": 0.95}]),
    )

    response = client.post(
        "/process/image",
        files={"file": ("test.jpg", io.BytesIO(b"fake-image-bytes"), "image/jpeg")},
    )

    assert response.status_code == 200
    assert response.json()["scam_detection"] == {
        "model": "lilju",
        "score": 0.82,
        "evidence": [{"sentence": "계좌번호를 알려주세요", "score": 0.95}],
    }


def test_process_image_omits_scam_detection_when_no_text(monkeypatch):
    monkeypatch.setattr(
        image_router, "run_spai_inference", lambda data, filename: SpaiResult(0.87, None)
    )
    monkeypatch.setattr(
        image_router, "run_scam_inference_image", lambda data, filename: ScamResult(None, [])
    )

    response = client.post(
        "/process/image",
        files={"file": ("test.jpg", io.BytesIO(b"fake-image-bytes"), "image/jpeg")},
    )

    assert response.json()["scam_detection"] is None


def test_process_image_ignores_scam_inference_failure(monkeypatch):
    monkeypatch.setattr(
        image_router, "run_spai_inference", lambda data, filename: SpaiResult(0.87, None)
    )

    def raise_scam_error(data, filename):
        raise ScamInferenceError("boom")

    monkeypatch.setattr(image_router, "run_scam_inference_image", raise_scam_error)

    response = client.post(
        "/process/image",
        files={"file": ("test.jpg", io.BytesIO(b"fake-image-bytes"), "image/jpeg")},
    )

    assert response.status_code == 200
    assert response.json()["scam_detection"] is None
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `python -m pytest tests/test_image_router.py -v`
Expected: FAIL (`ImportError`/`AttributeError` — `run_scam_inference_image`가 `image_router`에 아직 없음)

- [ ] **Step 4: `app/routers/image.py` 구현**

```python
import asyncio

from fastapi import APIRouter, File, HTTPException, UploadFile

from app.schemas import AIDetectionResult, ImageAnalysisResponse, ScamDetectionResult, ScamEvidence
from app.services.scam_runner import ScamInferenceError, ScamResult, run_scam_inference_image
from app.services.spai_runner import SpaiInferenceError, run_spai_inference

router = APIRouter()

ALLOWED_CONTENT_TYPES = {"image/jpeg", "image/png", "image/webp"}


@router.post("/process/image", response_model=ImageAnalysisResponse)
async def process_image(file: UploadFile = File(...)) -> ImageAnalysisResponse:
    if file.content_type not in ALLOWED_CONTENT_TYPES:
        raise HTTPException(
            status_code=415, detail=f"unsupported content type: {file.content_type}"
        )

    image_bytes = await file.read()
    if not image_bytes:
        raise HTTPException(status_code=400, detail="empty file")

    filename = file.filename or "input.jpg"
    ai_task = asyncio.create_task(asyncio.to_thread(run_spai_inference, image_bytes, filename))
    scam_task = asyncio.create_task(asyncio.to_thread(run_scam_inference_image, image_bytes, filename))

    try:
        ai_result = await ai_task
    except SpaiInferenceError as e:
        scam_task.cancel()
        raise HTTPException(status_code=502, detail=str(e)) from e

    try:
        scam_result = await scam_task
    except ScamInferenceError:
        # 사기감지는 best-effort - AI판독(SPAI)이 성공했으면 사기감지 실패로 전체 요청을
        # 실패시키지 않는다(evidenceImage/Grad-CAM과 동일 원칙, 2026-09-13 설계 확정).
        scam_result = ScamResult(None, [])

    scam_detection = (
        ScamDetectionResult(
            model="lilju",
            score=scam_result.score,
            evidence=[ScamEvidence(**e) for e in scam_result.evidence],
        )
        if scam_result.score is not None
        else None
    )

    return ImageAnalysisResponse(
        ai_detection=AIDetectionResult(
            model="spai", score=ai_result.score, evidence_image=ai_result.evidence_image
        ),
        scam_detection=scam_detection,
    )
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `python -m pytest tests/test_image_router.py -v`
Expected: PASS (8개 테스트)

- [ ] **Step 6: 커밋**

```bash
git add app/schemas.py app/routers/image.py tests/test_image_router.py
git commit -m "feat(fraud-risk): 이미지 분석에 scamDetection 병렬 실행 + 응답 필드 추가"
```

---

### Task 4: 음성 라우터 — 동일 패턴 적용

**Files:**
- Modify: `app/schemas.py`
- Modify: `app/routers/audio.py`
- Modify: `tests/test_audio_router.py`

**Interfaces:**
- Consumes: Task 1의 `run_scam_inference_audio`, `ScamResult`, `ScamInferenceError`; Task 3의 `ScamEvidence`, `ScamDetectionResult`(schemas.py, 재사용)
- Produces: `AudioAnalysisResponse.scam_detection: ScamDetectionResult | None`

- [ ] **Step 1: `app/schemas.py`의 `AudioAnalysisResponse`를 다음으로 교체**

```python
class AudioAnalysisResponse(BaseModel):
    ai_detection: AudioDetectionResult
    scam_detection: ScamDetectionResult | None = None
```

- [ ] **Step 2: 실패하는 테스트 작성 — `tests/test_audio_router.py`**

기존 파일을 열어 기존 테스트 함수들의 이름과 assert 구조를 확인한 뒤(`tests/test_image_router.py`와 동일한 `monkeypatch.setattr(audio_router, ...)` 패턴), Task 3의 Step 2와 동일한 방식으로:
1. 파일 상단에 `from app.services.scam_runner import ScamInferenceError, ScamResult` 추가.
2. 기존의 성공 경로 테스트들(오디오 분석이 정상 완료되는 케이스) 각각에 `monkeypatch.setattr(audio_router, "run_scam_inference_audio", lambda data, filename: ScamResult(None, []))` 한 줄씩 추가.
3. Task 3의 `test_process_image_returns_scam_detection_when_present`/`test_process_image_omits_scam_detection_when_no_text`/`test_process_image_ignores_scam_inference_failure` 세 테스트를 `/process/audio`, `audio_router`, `run_scam_inference_audio`, 오디오 파일(`("test.wav", ..., "audio/wav")`) 기준으로 그대로 옮겨 추가.

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `python -m pytest tests/test_audio_router.py -v`
Expected: FAIL

- [ ] **Step 4: `app/routers/audio.py` 구현**

```python
import asyncio

from fastapi import APIRouter, File, HTTPException, UploadFile

from app.schemas import AudioAnalysisResponse, AudioDetectionResult, Evidence, ScamDetectionResult, ScamEvidence
from app.services.antideepfake_runner import AntiDeepfakeInferenceError, run_antideepfake_inference
from app.services.scam_runner import ScamInferenceError, ScamResult, run_scam_inference_audio

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

    filename = file.filename or "input.wav"
    ai_task = asyncio.create_task(asyncio.to_thread(run_antideepfake_inference, audio_bytes, filename))
    scam_task = asyncio.create_task(asyncio.to_thread(run_scam_inference_audio, audio_bytes, filename))

    try:
        ai_result = await ai_task
    except AntiDeepfakeInferenceError as e:
        scam_task.cancel()
        raise HTTPException(status_code=502, detail=str(e)) from e

    try:
        scam_result = await scam_task
    except ScamInferenceError:
        scam_result = ScamResult(None, [])

    evidence = [
        Evidence(
            title=e["title"],
            description=e["description"],
            tags=e["tags"],
            start_sec=e["start_sec"],
            end_sec=e["end_sec"],
        )
        for e in ai_result.evidence
    ]
    scam_detection = (
        ScamDetectionResult(
            model="lilju",
            score=scam_result.score,
            evidence=[ScamEvidence(**e) for e in scam_result.evidence],
        )
        if scam_result.score is not None
        else None
    )

    return AudioAnalysisResponse(
        ai_detection=AudioDetectionResult(model="antideepfake", score=ai_result.score, evidence=evidence),
        scam_detection=scam_detection,
    )
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `python -m pytest tests/test_audio_router.py -v`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add app/schemas.py app/routers/audio.py tests/test_audio_router.py
git commit -m "feat(fraud-risk): 음성 분석에 scamDetection 병렬 실행 + 응답 필드 추가"
```

---

### Task 5: 영상 라우터 — 동일 패턴 적용 (오디오 트랙 STT)

**Files:**
- Modify: `app/schemas.py`
- Modify: `app/routers/video.py`
- Modify: `tests/test_video_router.py`

**Interfaces:**
- Consumes: Task 1의 `run_scam_inference_video`(ffmpeg로 오디오 트랙 추출 후 STT), `ScamResult`, `ScamInferenceError`; Task 3의 `ScamEvidence`, `ScamDetectionResult`
- Produces: `VideoAnalysisResponse.scam_detection: ScamDetectionResult | None`

- [ ] **Step 1: `app/schemas.py`의 `VideoAnalysisResponse`를 다음으로 교체**

```python
class VideoAnalysisResponse(BaseModel):
    ai_detection: VideoDetectionResult
    scam_detection: ScamDetectionResult | None = None
```

- [ ] **Step 2: 실패하는 테스트 작성 — `tests/test_video_router.py`**

Task 4의 Step 2와 동일한 절차: `run_scam_inference_video`를 import, 기존 성공 경로 테스트에 `monkeypatch.setattr(video_router, "run_scam_inference_video", lambda data, filename: ScamResult(None, []))` 추가, `/process/video`·`video_router`·`run_scam_inference_video`·비디오 파일(`("test.mp4", ..., "video/mp4")`) 기준으로 scam_detection 유무/실패 테스트 3개 추가. (`NoFaceDetectedError` 422 케이스는 scam 로직과 무관하므로 그대로 둔다.)

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `python -m pytest tests/test_video_router.py -v`
Expected: FAIL

- [ ] **Step 4: `app/routers/video.py` 구현**

```python
import asyncio

from fastapi import APIRouter, File, HTTPException, UploadFile

from app.schemas import Evidence, ScamDetectionResult, ScamEvidence, VideoAnalysisResponse, VideoDetectionResult
from app.services.dfdc_runner import DfdcInferenceError, NoFaceDetectedError, run_dfdc_inference
from app.services.scam_runner import ScamInferenceError, ScamResult, run_scam_inference_video

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

    filename = file.filename or "input.mp4"
    ai_task = asyncio.create_task(asyncio.to_thread(run_dfdc_inference, video_bytes, filename))
    scam_task = asyncio.create_task(asyncio.to_thread(run_scam_inference_video, video_bytes, filename))

    try:
        ai_result = await ai_task
    except NoFaceDetectedError as e:
        scam_task.cancel()
        raise HTTPException(status_code=422, detail=str(e)) from e
    except DfdcInferenceError as e:
        scam_task.cancel()
        raise HTTPException(status_code=502, detail=str(e)) from e

    try:
        scam_result = await scam_task
    except ScamInferenceError:
        scam_result = ScamResult(None, [])

    evidence = [
        Evidence(
            title=e["title"],
            description=e["description"],
            tags=e["tags"],
            start_sec=e["start_sec"],
            end_sec=e["end_sec"],
        )
        for e in ai_result.evidence
    ]
    scam_detection = (
        ScamDetectionResult(
            model="lilju",
            score=scam_result.score,
            evidence=[ScamEvidence(**e) for e in scam_result.evidence],
        )
        if scam_result.score is not None
        else None
    )

    return VideoAnalysisResponse(
        ai_detection=VideoDetectionResult(
            model="dfdc", score=ai_result.score, evidence=evidence, evidence_image=ai_result.evidence_image
        ),
        scam_detection=scam_detection,
    )
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `python -m pytest tests/test_video_router.py -v`
Expected: PASS

- [ ] **Step 6: 전체 Python 테스트 스위트 실행**

Run: `python -m pytest -v`
Expected: PASS (기존 61개 + 이번에 추가된 테스트 전부)

- [ ] **Step 7: 커밋**

```bash
git add app/schemas.py app/routers/video.py tests/test_video_router.py
git commit -m "feat(fraud-risk): 영상 분석에 scamDetection 병렬 실행(오디오 트랙 STT) + 응답 필드 추가"
```

---

### Task 6: README.md — `text-extraction` conda env 설치 안내 추가

**Files:**
- Modify: `README.md`

이 Task는 코드 변경이 아니라 **사용자가 3060Ti 데스크탑에서 직접 실행할 명령 목록**을 문서화하는 것이다 — Claude Code는 이 리포를 로컬에서 편집만 하고, 실제 conda env 생성/설치는 데스크톱에서 사용자가 진행한다(`feedback_dont_execute_desktop_commands_locally`).

- [ ] **Step 1: 기존 README.md에서 SPAI/AntiDeepfake/dfdc 설치 섹션 형식을 확인**

기존 conda env 섹션들(`spai`, `antideepfake`, `dfdc`)의 제목 레벨, 명령어 블록 스타일, 환경변수 안내 방식을 그대로 따라간다.

- [ ] **Step 2: 새 섹션 추가**

기존 dfdc(영상) 섹션 뒤에 다음 섹션을 추가(정확한 표현은 기존 섹션들의 어투에 맞춰 다듬되, 다음 명령/내용을 반드시 포함):

```markdown
## 사기 위험도 분석(text-extraction) 설정

OCR(PaddleOCR)/STT(faster-whisper)/문장분리(kss)/사기감지(Lilju/voicephishing_kobert)를
위한 새 conda env를 만든다. GitHub repo clone은 필요 없다(전부 pip 패키지).

```powershell
conda create -n text-extraction python=3.10 -y
conda activate text-extraction
pip install paddlepaddle paddleocr faster-whisper transformers torch kss
```

`ffmpeg`는 음성 판독(AntiDeepfake) 설정에서 이미 설치했다면 그대로 재사용한다(영상에서
오디오 트랙을 뽑을 때도 동일하게 사용).

환경변수(모두 기본값이 있어 필수는 아님, 필요시 `setx`로 영구 설정):

- `TEXT_EXTRACTION_PYTHON`: `text-extraction` env의 python 실행 파일 전체 경로
  (예: `C:\Users\<user>\miniconda3\envs\text-extraction\python.exe`)
- `LILJU_MODEL_ID` (기본 `Lilju/voicephishing_kobert`)
- `PADDLEOCR_LANG` (기본 `korean`)
- `WHISPER_MODEL_SIZE` (기본 `large-v3`)
- `TEXT_EXTRACTION_TIMEOUT_SECONDS` (기본 300 - 실측 후 조정 필요)

최초 실행 시 HuggingFace에서 Lilju 가중치(약 370MB)를 자동 다운로드한다 - 비인증 요청은
rate limit이 있으므로 반복 실행이 많다면 `HF_TOKEN` 설정을 고려한다.
```

- [ ] **Step 3: 커밋**

```bash
git add README.md
git commit -m "docs: text-extraction conda env(사기감지) 설치 안내 추가"
```

---

## Spring 쪽 (veritae-server)

### Task 7: 새 도메인 타입 — `ScamDetectionResult`, `ScamEvidence`, `*AnalysisResult`

**Files:**
- Create: `src/main/java/com/veritae/veritae_server/detection/ScamEvidence.java`
- Create: `src/main/java/com/veritae/veritae_server/detection/ScamDetectionResult.java`
- Create: `src/main/java/com/veritae/veritae_server/detection/ImageAnalysisResult.java`
- Create: `src/main/java/com/veritae/veritae_server/detection/AudioAnalysisResult.java`
- Create: `src/main/java/com/veritae/veritae_server/detection/VideoAnalysisResult.java`
- Test: `src/test/java/com/veritae/veritae_server/detection/ScamDetectionTypesTest.java`

**Interfaces:**
- Produces: `ScamEvidence(String sentence, double score)`, `ScamDetectionResult(String model, double score, List<ScamEvidence> evidence)`, `ImageAnalysisResult(ImageDetectionResult aiDetection, ScamDetectionResult scamDetection)`, `AudioAnalysisResult(AudioDetectionResult aiDetection, ScamDetectionResult scamDetection)`, `VideoAnalysisResult(VideoDetectionResult aiDetection, ScamDetectionResult scamDetection)`

- [ ] **Step 1: 실패하는 테스트 작성 — `ScamDetectionTypesTest.java`**

```java
package com.veritae.veritae_server.detection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScamDetectionTypesTest {

    @Test
    void scamDetectionResult_exposesFieldsViaRecordAccessors() {
        var evidence = new ScamEvidence("계좌번호를 알려주세요", 0.95);
        var result = new ScamDetectionResult("lilju", 0.82, List.of(evidence));

        assertThat(result.model()).isEqualTo("lilju");
        assertThat(result.score()).isEqualTo(0.82);
        assertThat(result.evidence()).containsExactly(evidence);
    }

    @Test
    void imageAnalysisResult_allowsNullScamDetection() {
        var aiDetection = new ImageDetectionResult("spai", 0.1, null);

        var result = new ImageAnalysisResult(aiDetection, null);

        assertThat(result.aiDetection()).isEqualTo(aiDetection);
        assertThat(result.scamDetection()).isNull();
    }

    @Test
    void audioAnalysisResult_andVideoAnalysisResult_exposeAiAndScamDetection() {
        var scamDetection = new ScamDetectionResult("lilju", 0.3, List.of());

        var audioResult = new AudioAnalysisResult(new AudioDetectionResult("antideepfake", 0.1, List.of()), scamDetection);
        var videoResult = new VideoAnalysisResult(new VideoDetectionResult("dfdc", 0.2, List.of(), null), scamDetection);

        assertThat(audioResult.scamDetection()).isEqualTo(scamDetection);
        assertThat(videoResult.scamDetection()).isEqualTo(scamDetection);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.detection.ScamDetectionTypesTest"`
Expected: FAIL (컴파일 에러 — 타입들이 아직 없음)

- [ ] **Step 3: 타입 구현**

`ScamEvidence.java`:
```java
package com.veritae.veritae_server.detection;

/**
 * 사기감지 근거 카드 한 장 - 위험하다고 판단된 문장 원문과 그 문장의 개별 확률만 담는다.
 * 유형 라벨("기관사칭" 등)은 포함하지 않는다 - 라벨을 만들려면 키워드/규칙 기반 분류가
 * 필요한데 이미 사용자가 거절한 방향이라 채택하지 않았다(2026-09-13 설계).
 */
public record ScamEvidence(String sentence, double score) {
}
```

`ScamDetectionResult.java`:
```java
package com.veritae.veritae_server.detection;

import java.util.List;

/**
 * 사기(보이스피싱 등) 위험도 판독 결과. 텍스트가 전혀 추출되지 않으면 이 타입 자체가
 * null이 된다 - AnalysisResponse에서 aiDetection과 나란한 형제 필드다(중첩되지 않음).
 */
public record ScamDetectionResult(String model, double score, List<ScamEvidence> evidence) {
}
```

`ImageAnalysisResult.java`:
```java
package com.veritae.veritae_server.detection;

/**
 * 이미지 분석 한 번의 결과 전체(AI판독 + 사기감지). 데스크톱 탐지 서버가 한 응답에
 * ai_detection/scam_detection을 같이 담아 보내주므로, HTTP 호출은 여전히 한 번뿐이다.
 */
public record ImageAnalysisResult(ImageDetectionResult aiDetection, ScamDetectionResult scamDetection) {
}
```

`AudioAnalysisResult.java`:
```java
package com.veritae.veritae_server.detection;

public record AudioAnalysisResult(AudioDetectionResult aiDetection, ScamDetectionResult scamDetection) {
}
```

`VideoAnalysisResult.java`:
```java
package com.veritae.veritae_server.detection;

public record VideoAnalysisResult(VideoDetectionResult aiDetection, ScamDetectionResult scamDetection) {
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.detection.ScamDetectionTypesTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/detection/Scam*.java \
        src/main/java/com/veritae/veritae_server/detection/*AnalysisResult.java \
        src/test/java/com/veritae/veritae_server/detection/ScamDetectionTypesTest.java
git commit -m "feat(fraud-risk): ScamDetectionResult/ScamEvidence + *AnalysisResult 조합 타입 추가"
```

---

### Task 8: `DetectionClient`/`SpaiHttpDetectionClient` — 이미지 scam_detection 파싱

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/detection/DetectionClient.java`
- Modify: `src/main/java/com/veritae/veritae_server/detection/spai/SpaiHttpDetectionClient.java`
- Modify: `src/test/java/com/veritae/veritae_server/detection/spai/SpaiHttpDetectionClientTest.java`

**Interfaces:**
- Consumes: Task 7의 `ImageAnalysisResult`, `ScamDetectionResult`, `ScamEvidence`
- Produces: `DetectionClient.detectImage(...)` 반환 타입이 `ImageDetectionResult` → `ImageAnalysisResult`로 변경

- [ ] **Step 1: 실패하는 테스트로 교체 — `SpaiHttpDetectionClientTest.java`**

전체 파일을 다음으로 교체:

```java
package com.veritae.veritae_server.detection.spai;

import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
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

class SpaiHttpDetectionClientTest {

    @Test
    void detectImage_withSuccessResponse_shouldParseScore() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"ai_detection\": {\"model\": \"spai\", \"score\": 0.87}}",
                        MediaType.APPLICATION_JSON));
        SpaiHttpDetectionClient client = new SpaiHttpDetectionClient(builder.build());

        ImageAnalysisResult result = client.detectImage("fake-bytes".getBytes(), "test.jpg", "image/jpeg");

        assertThat(result.aiDetection().model()).isEqualTo("spai");
        assertThat(result.aiDetection().score()).isEqualTo(0.87);
        assertThat(result.scamDetection()).isNull();
        server.verify();
    }

    @Test
    void detectImage_withEvidenceImageInResponse_shouldParseEvidenceImage() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"ai_detection\": {\"model\": \"spai\", \"score\": 0.87, "
                                + "\"evidence_image\": \"base64data\"}}",
                        MediaType.APPLICATION_JSON));
        SpaiHttpDetectionClient client = new SpaiHttpDetectionClient(builder.build());

        ImageAnalysisResult result = client.detectImage("fake-bytes".getBytes(), "test.jpg", "image/jpeg");

        assertThat(result.aiDetection().evidenceImage()).isEqualTo("base64data");
    }

    @Test
    void detectImage_withScamDetectionInResponse_shouldParseScamDetection() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"ai_detection\": {\"model\": \"spai\", \"score\": 0.87}, "
                                + "\"scam_detection\": {\"model\": \"lilju\", \"score\": 0.82, "
                                + "\"evidence\": [{\"sentence\": \"계좌번호를 알려주세요\", \"score\": 0.95}]}}",
                        MediaType.APPLICATION_JSON));
        SpaiHttpDetectionClient client = new SpaiHttpDetectionClient(builder.build());

        ImageAnalysisResult result = client.detectImage("fake-bytes".getBytes(), "test.jpg", "image/jpeg");

        assertThat(result.scamDetection().model()).isEqualTo("lilju");
        assertThat(result.scamDetection().score()).isEqualTo(0.82);
        assertThat(result.scamDetection().evidence()).hasSize(1);
        assertThat(result.scamDetection().evidence().get(0).sentence()).isEqualTo("계좌번호를 알려주세요");
    }

    @Test
    void detectImage_withoutScamDetectionInResponse_shouldParseNull() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"ai_detection\": {\"model\": \"spai\", \"score\": 0.87}}",
                        MediaType.APPLICATION_JSON));
        SpaiHttpDetectionClient client = new SpaiHttpDetectionClient(builder.build());

        ImageAnalysisResult result = client.detectImage("fake-bytes".getBytes(), "test.jpg", "image/jpeg");

        assertThat(result.scamDetection()).isNull();
    }

    @Test
    void detectImage_whenServerReturnsError_shouldThrowDetectionServiceException() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andRespond(withServerError());
        SpaiHttpDetectionClient client = new SpaiHttpDetectionClient(builder.build());

        assertThatThrownBy(() -> client.detectImage("fake-bytes".getBytes(), "test.jpg", "image/jpeg"))
                .isInstanceOf(DetectionServiceException.class);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.detection.spai.SpaiHttpDetectionClientTest"`
Expected: FAIL (컴파일 에러 — `detectImage`가 아직 `ImageDetectionResult`를 반환)

- [ ] **Step 3: `DetectionClient.java` 반환 타입 변경**

```java
package com.veritae.veritae_server.detection;

/**
 * AI 생성물 탐지 + 사기 위험도 판독 요청을 한 번에 처리하는 포트. 지금은 SPAI를
 * 셀프호스팅하는 {@link com.veritae.veritae_server.detection.spai.SpaiHttpDetectionClient}가
 * 유일한 구현체지만, 포맷별로 유료 API 기반 구현체로 교체할 수 있도록 호출부는 이
 * 인터페이스만 알면 되게 분리한다.
 */
public interface DetectionClient {

    ImageAnalysisResult detectImage(byte[] imageBytes, String filename, String contentType);
}
```

- [ ] **Step 4: `SpaiHttpDetectionClient.java` 구현**

```java
package com.veritae.veritae_server.detection.spai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.veritae.veritae_server.detection.DetectionClient;
import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
import com.veritae.veritae_server.detection.ImageDetectionResult;
import com.veritae.veritae_server.detection.ScamDetectionResult;
import com.veritae.veritae_server.detection.ScamEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * {@link DetectionClient} 의 SPAI(셀프호스팅) 구현체. 탐지 서버(veritae-detection-server,
 * 3060Ti 데스크탑)의 POST /process/image 를 호출한다. 이미지 판독(SPAI)과 사기감지
 * (Lilju)를 데스크탑이 내부적으로 병렬 실행해 한 응답에 ai_detection/scam_detection을
 * 같이 담아 보내므로, HTTP 호출은 지금처럼 한 번만 한다(2026-09-13).
 */
@Component
@RequiredArgsConstructor
public class SpaiHttpDetectionClient implements DetectionClient {

    private final RestClient detectionRestClient;

    @Override
    public ImageAnalysisResult detectImage(byte[] imageBytes, String filename, String contentType) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, MediaType.parseMediaType(contentType));

        try {
            SpaiResponse response = detectionRestClient.post()
                    .uri("/process/image")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(builder.build())
                    .retrieve()
                    .body(SpaiResponse.class);

            if (response == null || response.aiDetection() == null) {
                throw new DetectionServiceException("탐지 서버 응답이 비어 있습니다.", null);
            }
            var aiDetection = new ImageDetectionResult(
                    response.aiDetection().model(),
                    response.aiDetection().score(),
                    response.aiDetection().evidenceImage());
            return new ImageAnalysisResult(aiDetection, toScamDetection(response.scamDetection()));
        } catch (RestClientException e) {
            throw new DetectionServiceException("탐지 서버 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }

    private ScamDetectionResult toScamDetection(ScamDetectionDto dto) {
        if (dto == null) {
            return null;
        }
        List<ScamEvidence> evidence = dto.evidence().stream()
                .map(e -> new ScamEvidence(e.sentence(), e.score()))
                .toList();
        return new ScamDetectionResult(dto.model(), dto.score(), evidence);
    }

    private record SpaiResponse(
            @JsonProperty("ai_detection") AiDetection aiDetection,
            @JsonProperty("scam_detection") ScamDetectionDto scamDetection) {
    }

    private record AiDetection(
            String model,
            double score,
            @JsonProperty("evidence_image") String evidenceImage) {
    }

    private record ScamDetectionDto(String model, double score, List<ScamEvidenceDto> evidence) {
    }

    private record ScamEvidenceDto(String sentence, double score) {
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.detection.spai.SpaiHttpDetectionClientTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/detection/DetectionClient.java \
        src/main/java/com/veritae/veritae_server/detection/spai/SpaiHttpDetectionClient.java \
        src/test/java/com/veritae/veritae_server/detection/spai/SpaiHttpDetectionClientTest.java
git commit -m "feat(fraud-risk): DetectionClient/SpaiHttpDetectionClient에 scamDetection 파싱 추가"
```

---

### Task 9: `AudioDetectionClient`/`AntiDeepfakeHttpDetectionClient` — 동일 패턴 적용

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/detection/AudioDetectionClient.java`
- Modify: `src/main/java/com/veritae/veritae_server/detection/antideepfake/AntiDeepfakeHttpDetectionClient.java`
- Modify: `src/test/java/com/veritae/veritae_server/detection/antideepfake/AntiDeepfakeHttpDetectionClientTest.java`

**Interfaces:**
- Consumes: Task 7의 `AudioAnalysisResult`, `ScamDetectionResult`, `ScamEvidence`
- Produces: `AudioDetectionClient.detectAudio(...)` 반환 타입이 `AudioDetectionResult` → `AudioAnalysisResult`로 변경

- [ ] **Step 1: `AntiDeepfakeHttpDetectionClientTest.java`를 Task 8의 Step 1과 동일한 방식으로 교체**

기존 테스트 케이스들의 `AudioDetectionResult result = client.detectAudio(...)`를 `AudioAnalysisResult result = ...`로, `result.model()`/`result.score()`/`result.evidence()`를 `result.aiDetection().model()` 등으로 바꾸고, Task 8의 `detectImage_withScamDetectionInResponse_shouldParseScamDetection`/`detectImage_withoutScamDetectionInResponse_shouldParseNull`과 동일한 테스트 2개를 `/process/audio` 응답 형식(`ai_detection.evidence`가 배열인 점 유의) 기준으로 추가.

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.detection.antideepfake.AntiDeepfakeHttpDetectionClientTest"`
Expected: FAIL

- [ ] **Step 3: `AudioDetectionClient.java` 반환 타입 변경**

```java
package com.veritae.veritae_server.detection;

public interface AudioDetectionClient {

    AudioAnalysisResult detectAudio(byte[] audioBytes, String filename, String contentType);
}
```

- [ ] **Step 4: `AntiDeepfakeHttpDetectionClient.java` 구현**

```java
package com.veritae.veritae_server.detection.antideepfake;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.veritae.veritae_server.detection.AudioAnalysisResult;
import com.veritae.veritae_server.detection.AudioDetectionClient;
import com.veritae.veritae_server.detection.AudioDetectionResult;
import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.Evidence;
import com.veritae.veritae_server.detection.ScamDetectionResult;
import com.veritae.veritae_server.detection.ScamEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AntiDeepfakeHttpDetectionClient implements AudioDetectionClient {

    private final RestClient detectionRestClient;

    @Override
    public AudioAnalysisResult detectAudio(byte[] audioBytes, String filename, String contentType) {
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
            var aiDetection = new AudioDetectionResult(response.aiDetection().model(), response.aiDetection().score(), evidence);
            return new AudioAnalysisResult(aiDetection, toScamDetection(response.scamDetection()));
        } catch (RestClientException e) {
            throw new DetectionServiceException("탐지 서버 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }

    private ScamDetectionResult toScamDetection(ScamDetectionDto dto) {
        if (dto == null) {
            return null;
        }
        List<ScamEvidence> evidence = dto.evidence().stream()
                .map(e -> new ScamEvidence(e.sentence(), e.score()))
                .toList();
        return new ScamDetectionResult(dto.model(), dto.score(), evidence);
    }

    private record AntiDeepfakeResponse(
            @JsonProperty("ai_detection") AiDetection aiDetection,
            @JsonProperty("scam_detection") ScamDetectionDto scamDetection) {
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

    private record ScamDetectionDto(String model, double score, List<ScamEvidenceDto> evidence) {
    }

    private record ScamEvidenceDto(String sentence, double score) {
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.detection.antideepfake.AntiDeepfakeHttpDetectionClientTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/detection/AudioDetectionClient.java \
        src/main/java/com/veritae/veritae_server/detection/antideepfake/AntiDeepfakeHttpDetectionClient.java \
        src/test/java/com/veritae/veritae_server/detection/antideepfake/AntiDeepfakeHttpDetectionClientTest.java
git commit -m "feat(fraud-risk): AudioDetectionClient/AntiDeepfakeHttpDetectionClient에 scamDetection 파싱 추가"
```

---

### Task 10: `VideoDetectionClient`/`DfdcHttpDetectionClient` — 동일 패턴 적용

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/detection/VideoDetectionClient.java`
- Modify: `src/main/java/com/veritae/veritae_server/detection/dfdc/DfdcHttpDetectionClient.java`
- Modify: `src/test/java/com/veritae/veritae_server/detection/dfdc/DfdcHttpDetectionClientTest.java`

**Interfaces:**
- Consumes: Task 7의 `VideoAnalysisResult`, `ScamDetectionResult`, `ScamEvidence`
- Produces: `VideoDetectionClient.detectVideo(...)` 반환 타입이 `VideoDetectionResult` → `VideoAnalysisResult`로 변경

- [ ] **Step 1: `DfdcHttpDetectionClientTest.java`를 Task 9의 Step 1과 동일한 방식으로 교체** (422/`NoFaceDetectedException` 테스트는 scam 로직과 무관하므로 그대로 유지)

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.detection.dfdc.DfdcHttpDetectionClientTest"`
Expected: FAIL

- [ ] **Step 3: `VideoDetectionClient.java` 반환 타입 변경**

```java
package com.veritae.veritae_server.detection;

public interface VideoDetectionClient {

    VideoAnalysisResult detectVideo(byte[] videoBytes, String filename, String contentType);
}
```

- [ ] **Step 4: `DfdcHttpDetectionClient.java` 구현**

```java
package com.veritae.veritae_server.detection.dfdc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.Evidence;
import com.veritae.veritae_server.detection.NoFaceDetectedException;
import com.veritae.veritae_server.detection.ScamDetectionResult;
import com.veritae.veritae_server.detection.ScamEvidence;
import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.VideoDetectionClient;
import com.veritae.veritae_server.detection.VideoDetectionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Component
public class DfdcHttpDetectionClient implements VideoDetectionClient {

    private final RestClient videoDetectionRestClient;

    public DfdcHttpDetectionClient(@Qualifier("videoDetectionRestClient") RestClient videoDetectionRestClient) {
        this.videoDetectionRestClient = videoDetectionRestClient;
    }

    @Override
    public VideoAnalysisResult detectVideo(byte[] videoBytes, String filename, String contentType) {
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
            var aiDetection = new VideoDetectionResult(
                    response.aiDetection().model(),
                    response.aiDetection().score(),
                    evidence,
                    response.aiDetection().evidenceImage());
            return new VideoAnalysisResult(aiDetection, toScamDetection(response.scamDetection()));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 422) {
                throw new NoFaceDetectedException();
            }
            throw new DetectionServiceException("탐지 서버 호출에 실패했습니다: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new DetectionServiceException("탐지 서버 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }

    private ScamDetectionResult toScamDetection(ScamDetectionDto dto) {
        if (dto == null) {
            return null;
        }
        List<ScamEvidence> evidence = dto.evidence().stream()
                .map(e -> new ScamEvidence(e.sentence(), e.score()))
                .toList();
        return new ScamDetectionResult(dto.model(), dto.score(), evidence);
    }

    private record DfdcResponse(
            @JsonProperty("ai_detection") AiDetection aiDetection,
            @JsonProperty("scam_detection") ScamDetectionDto scamDetection) {
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

    private record ScamDetectionDto(String model, double score, List<ScamEvidenceDto> evidence) {
    }

    private record ScamEvidenceDto(String sentence, double score) {
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.detection.dfdc.DfdcHttpDetectionClientTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/detection/VideoDetectionClient.java \
        src/main/java/com/veritae/veritae_server/detection/dfdc/DfdcHttpDetectionClient.java \
        src/test/java/com/veritae/veritae_server/detection/dfdc/DfdcHttpDetectionClientTest.java
git commit -m "feat(fraud-risk): VideoDetectionClient/DfdcHttpDetectionClient에 scamDetection 파싱 추가"
```

---

### Task 11: `ImageAnalysisService`/`AudioAnalysisService` — 반환 타입 갱신

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/analysis/ImageAnalysisService.java`
- Modify: `src/main/java/com/veritae/veritae_server/analysis/AudioAnalysisService.java`
- Modify: `src/test/java/com/veritae/veritae_server/analysis/ImageAnalysisServiceTest.java`
- Modify: `src/test/java/com/veritae/veritae_server/analysis/AudioAnalysisServiceTest.java`

**Interfaces:**
- Consumes: Task 8/9의 `DetectionClient`/`AudioDetectionClient` (반환 타입 변경됨)
- Produces: `ImageAnalysisService.analyzeImage(...)` → `ImageAnalysisResult`, `AudioAnalysisService.analyzeAudio(...)` → `AudioAnalysisResult`

- [ ] **Step 1: 실패하는 테스트로 교체 — `ImageAnalysisServiceTest.java`**

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.DetectionClient;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
import com.veritae.veritae_server.detection.ImageDetectionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageAnalysisServiceTest {

    @Mock
    private DetectionClient detectionClient;

    private ImageAnalysisService imageAnalysisService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        imageAnalysisService = new ImageAnalysisService(detectionClient);
    }

    @Test
    void analyzeImage_withValidJpeg_shouldReturnAnalysisResult() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());
        var expected = new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, null), null);
        when(detectionClient.detectImage(file.getBytes(), "test.jpg", "image/jpeg")).thenReturn(expected);

        ImageAnalysisResult result = imageAnalysisService.analyzeImage(file);

        assertThat(result.aiDetection().model()).isEqualTo("spai");
        assertThat(result.aiDetection().score()).isEqualTo(0.87);
    }

    @Test
    void analyzeImage_withEmptyFile_shouldThrowInvalidImageFileException() {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> imageAnalysisService.analyzeImage(file))
                .isInstanceOf(InvalidImageFileException.class);
        verifyNoInteractions(detectionClient);
    }

    @Test
    void analyzeImage_withUnsupportedContentType_shouldThrowInvalidImageFileException() {
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "not-an-image".getBytes());

        assertThatThrownBy(() -> imageAnalysisService.analyzeImage(file))
                .isInstanceOf(InvalidImageFileException.class);
        verifyNoInteractions(detectionClient);
    }
}
```

`AudioAnalysisServiceTest.java`도 기존 파일을 열어 동일한 방식(`AudioDetectionResult`→`AudioAnalysisResult`, mock 반환값 조립, `result.aiDetection().*()`로 assert)으로 고친다.

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.ImageAnalysisServiceTest" --tests "com.veritae.veritae_server.analysis.AudioAnalysisServiceTest"`
Expected: FAIL

- [ ] **Step 3: `ImageAnalysisService.java` 구현**

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.DetectionClient;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ImageAnalysisService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final DetectionClient detectionClient;

    public ImageAnalysisResult analyzeImage(MultipartFile file) {
        validate(file);
        try {
            return detectionClient.detectImage(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            throw new UncheckedIOException("업로드된 파일을 읽을 수 없습니다.", e);
        }
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidImageFileException("빈 파일은 분석할 수 없습니다.");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new InvalidImageFileException("지원하지 않는 파일 형식입니다: " + file.getContentType());
        }
    }
}
```

- [ ] **Step 4: `AudioAnalysisService.java` 구현**

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.AudioAnalysisResult;
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

    public AudioAnalysisResult analyzeAudio(MultipartFile file) {
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
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new InvalidAudioFileException("지원하지 않는 파일 형식입니다: " + contentType);
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidAudioFileException("파일 용량이 25MB를 초과합니다.");
        }
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.ImageAnalysisServiceTest" --tests "com.veritae.veritae_server.analysis.AudioAnalysisServiceTest"`
Expected: PASS

(이 시점에서 `ImageAnalysisService`/`AudioAnalysisService`는 컴파일되지만, 이들을 호출하는 `AnalysisApiMapper`/`AnalysisApiDelegateImpl`는 아직 옛 타입을 참조하므로 전체 빌드는 Task 14까지 깨져 있는 게 정상이다 — 개별 테스트 클래스만 지정 실행해서 확인한다.)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/analysis/ImageAnalysisService.java \
        src/main/java/com/veritae/veritae_server/analysis/AudioAnalysisService.java \
        src/test/java/com/veritae/veritae_server/analysis/ImageAnalysisServiceTest.java \
        src/test/java/com/veritae/veritae_server/analysis/AudioAnalysisServiceTest.java
git commit -m "feat(fraud-risk): ImageAnalysisService/AudioAnalysisService가 *AnalysisResult 반환하도록 변경"
```

---

### Task 12: 영상 job 파이프라인 — `VideoAnalysisResult` 저장/조회

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorker.java`
- Modify: `src/main/java/com/veritae/veritae_server/analysis/VideoAnalysisService.java`
- Modify: `src/main/java/com/veritae/veritae_server/analysis/AnalysisJobView.java`
- Modify: `src/test/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorkerTest.java`
- Modify: `src/test/java/com/veritae/veritae_server/analysis/VideoAnalysisServiceTest.java`

**Interfaces:**
- Consumes: Task 10의 `VideoDetectionClient.detectVideo(...)` → `VideoAnalysisResult`
- Produces: `AnalysisJobView.result()` 타입이 `VideoDetectionResult` → `VideoAnalysisResult`로 변경 (job의 `resultJson`은 이제 `VideoAnalysisResult` 전체를 직렬화)

- [ ] **Step 1: 두 테스트 파일을 열어 기존 케이스에서 `VideoDetectionResult`를 사용하는 부분을 `VideoAnalysisResult(VideoDetectionResult, ScamDetectionResult)`로 감싸도록 갱신**

예를 들어 `VideoAnalysisAsyncWorkerTest.java`에서 `when(videoDetectionClient.detectVideo(...)).thenReturn(new VideoDetectionResult(...))`로 되어 있던 부분을 `.thenReturn(new VideoAnalysisResult(new VideoDetectionResult(...), null))`로 바꾸고, `writeResultJson`/`markCompleted` 관련 assert가 있다면 역직렬화 대상 타입도 `VideoAnalysisResult.class`로 맞춘다. `VideoAnalysisServiceTest.java`의 `getJob` 관련 케이스도 저장된 `resultJson`을 만들 때 `VideoAnalysisResult`를 직렬화하도록 바꾸고, `view.result()` assert를 `view.result().aiDetection().*()` 형태로 갱신한다.

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.VideoAnalysisAsyncWorkerTest" --tests "com.veritae.veritae_server.analysis.VideoAnalysisServiceTest"`
Expected: FAIL

- [ ] **Step 3: `VideoAnalysisAsyncWorker.java` 구현**

```java
package com.veritae.veritae_server.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.veritae.veritae_server.detection.NoFaceDetectedException;
import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.VideoDetectionClient;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJob;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoAnalysisAsyncWorker {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final VideoDetectionClient videoDetectionClient;
    private final AnalysisJobRepository analysisJobRepository;

    @Async("videoAnalysisExecutor")
    public void process(UUID jobId, byte[] videoBytes, String filename, String contentType) {
        AnalysisJob job = analysisJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("방금 생성한 job을 찾을 수 없습니다: " + jobId));
        job.markProcessing();
        analysisJobRepository.save(job);

        try {
            VideoAnalysisResult result = videoDetectionClient.detectVideo(videoBytes, filename, contentType);
            job.markCompleted(writeResultJson(result));
        } catch (NoFaceDetectedException e) {
            log.info("영상 분석: 얼굴 미검출 jobId={}", jobId);
            job.markFailed("NO_FACE_DETECTED", "영상에서 얼굴을 찾을 수 없습니다. 얼굴이 잘 보이는 영상으로 다시 시도해주세요.");
        } catch (Exception e) {
            log.error("영상 분석 실패 jobId={}", jobId, e);
            job.markFailed("ANALYSIS_FAILED", "영상 분석 중 오류가 발생했습니다.");
        }
        analysisJobRepository.save(job);
    }

    private String writeResultJson(VideoAnalysisResult result) {
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
```

- [ ] **Step 4: `AnalysisJobView.java` 구현**

```java
package com.veritae.veritae_server.analysis;

import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobStatus;

import java.util.UUID;

public record AnalysisJobView(
        UUID jobId, AnalysisJobStatus status, VideoAnalysisResult result, String errorCode, String errorMessage) {
}
```

- [ ] **Step 5: `VideoAnalysisService.java`의 `readResultJson` 갱신**

`readResultJson` 메서드 시그니처와 본문만 교체(나머지는 그대로):

```java
    private VideoAnalysisResult readResultJson(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, VideoAnalysisResult.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
```

그리고 `getJob` 메서드 안의 지역변수 타입도 `VideoDetectionResult result` → `VideoAnalysisResult result`로, import도 `com.veritae.veritae_server.detection.VideoAnalysisResult`로 바꾼다(`VideoDetectionResult` import는 더 이상 필요 없으면 제거).

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.analysis.VideoAnalysisAsyncWorkerTest" --tests "com.veritae.veritae_server.analysis.VideoAnalysisServiceTest"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorker.java \
        src/main/java/com/veritae/veritae_server/analysis/VideoAnalysisService.java \
        src/main/java/com/veritae/veritae_server/analysis/AnalysisJobView.java \
        src/test/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorkerTest.java \
        src/test/java/com/veritae/veritae_server/analysis/VideoAnalysisServiceTest.java
git commit -m "feat(fraud-risk): 영상 job 파이프라인이 VideoAnalysisResult를 저장/조회하도록 변경"
```

---

### Task 13: `openapi.yaml` — `ScamDetectionResult`/`ScamEvidence` 스키마 + 응답 필드 추가

**Files:**
- Modify: `src/main/resources/openapi.yaml`

**Interfaces:**
- Produces: openapi-generator가 생성할 `com.veritae.veritae_server.openapi.model.ScamDetectionResult`, `ScamEvidence` 클래스, `ImageAnalysisResponse`/`AudioAnalysisResponse`/`AnalysisJobResponse`의 `.scamDetection(...)` 플루언트 세터

- [ ] **Step 1: `ImageAnalysisResponse` 스키마에 `scamDetection` 추가**

`ImageAnalysisResponse:` 블록을 다음으로 교체:

```yaml
    ImageAnalysisResponse:
      type: object
      required: [aiDetection]
      properties:
        aiDetection:
          $ref: '#/components/schemas/ImageDetectionResult'
        scamDetection:
          allOf:
            - $ref: '#/components/schemas/ScamDetectionResult'
          nullable: true
          description: 사기(보이스피싱 등) 위험도 판독 결과. 텍스트가 전혀 추출되지 않으면 없음(null).
```

- [ ] **Step 2: `AudioAnalysisResponse` 스키마에 동일하게 추가**

```yaml
    AudioAnalysisResponse:
      type: object
      required: [aiDetection]
      properties:
        aiDetection:
          $ref: '#/components/schemas/AudioDetectionResult'
        scamDetection:
          allOf:
            - $ref: '#/components/schemas/ScamDetectionResult'
          nullable: true
          description: 사기(보이스피싱 등) 위험도 판독 결과. 텍스트가 전혀 추출되지 않으면 없음(null).
```

- [ ] **Step 3: `AnalysisJobResponse` 스키마의 `properties`에 `scamDetection` 추가**

`aiDetection` 항목 바로 뒤에 삽입:

```yaml
        scamDetection:
          allOf:
            - $ref: '#/components/schemas/ScamDetectionResult'
          nullable: true
          description: status가 COMPLETED이고 텍스트가 추출됐을 때만 채워진다.
```

- [ ] **Step 4: 새 스키마 `ScamDetectionResult`/`ScamEvidence` 추가**

`VideoDetectionResult:` 스키마 블록과 `Evidence:` 스키마 블록 사이에 삽입:

```yaml
    ScamDetectionResult:
      type: object
      required: [model, score, evidence]
      description: 사기(보이스피싱 등) 위험도 판독 결과. 텍스트가 전혀 추출되지 않으면 이 필드 자체가 없다(null).
      properties:
        model:
          type: string
          nullable: false
          description: 사기감지에 사용된 모델 이름.
          example: lilju
        score:
          type: number
          format: double
          nullable: false
          minimum: 0
          maximum: 1
          description: 사기(보이스피싱 등)일 위험도(0~1). 문장별 확률의 최댓값x0.7 + 평균x0.3으로 집계.
          example: 0.82
        evidence:
          type: array
          nullable: false
          description: 위험하다고 판단된 문장 근거 카드 목록(개별 확률 0.5 이상만 포함). 근거가 없으면 빈 배열.
          items:
            $ref: '#/components/schemas/ScamEvidence'

    ScamEvidence:
      type: object
      required: [sentence, score]
      description: >-
        사기감지 근거 카드 한 장. 위험 문장 원문과 그 문장의 개별 확률만 담는다 - 유형
        라벨(예: 기관사칭)은 룰 기반 분류가 필요해 포함하지 않는다.
      properties:
        sentence:
          type: string
          nullable: false
          description: 위험하다고 판단된 문장 원문.
          example: 지금 바로 계좌번호와 비밀번호를 알려주셔야 합니다.
        score:
          type: number
          format: double
          nullable: false
          minimum: 0
          maximum: 1
          description: 이 문장이 사기(보이스피싱 등)일 확률(0~1).
          example: 0.95
```

- [ ] **Step 5: openapi-generator 재생성 + 컴파일 확인**

Run: `./gradlew openApiGenerate`
Expected: SUCCESS, `build/generated/.../openapi/model/ScamDetectionResult.java`, `ScamEvidence.java` 생성 확인

- [ ] **Step 6: 커밋**

```bash
git add src/main/resources/openapi.yaml
git commit -m "feat(fraud-risk): openapi.yaml에 ScamDetectionResult/ScamEvidence 스키마 및 응답 필드 추가"
```

---

### Task 14: `AnalysisApiMapper` — 응답 조립에 `scamDetection` 반영 + 전체 빌드 확인

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/api/AnalysisApiMapper.java`
- Modify: `src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java`

**Interfaces:**
- Consumes: Task 7~13의 모든 변경 (`ImageAnalysisResult`, `AudioAnalysisResult`, `VideoAnalysisResult`, 생성된 openapi 모델의 `ScamDetectionResult`/`ScamEvidence`)
- Produces: `AnalysisApiMapper.toResponse/toAudioResponse/toJobResponse`가 `scamDetection`까지 포함한 응답을 조립

- [ ] **Step 1: 실패하는 테스트 작성 — `AnalysisApiControllerTest.java`에 추가**

기존 파일을 열어 이미지/음성/영상 분석 성공 케이스 테스트들의 mock 설정(`when(imageAnalysisService.analyzeImage(any())).thenReturn(...)` 등)을 확인한 뒤, 각각 `ImageDetectionResult`/`AudioDetectionResult`/`VideoDetectionResult`를 반환하던 부분을 `ImageAnalysisResult`/`AudioAnalysisResult`/`VideoAnalysisResult`로 감싸도록 갱신하고, 다음 형태의 assert를 하나씩 추가:

```java
    @Test
    void analyzeImage_withScamDetection_shouldIncludeScamDetectionInResponse() throws Exception {
        var scamDetection = new ScamDetectionResult("lilju", 0.82, List.of(new ScamEvidence("계좌번호를 알려주세요", 0.95)));
        when(imageAnalysisService.analyzeImage(any()))
                .thenReturn(new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, null), scamDetection));

        mockMvc.perform(multipart("/api/v1/analysis/image")
                        .file("file", "fake-bytes".getBytes())
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .with(/* 기존 인증 설정 그대로 */))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scamDetection.model").value("lilju"))
                .andExpect(jsonPath("$.scamDetection.score").value(0.82))
                .andExpect(jsonPath("$.scamDetection.evidence[0].sentence").value("계좌번호를 알려주세요"));
    }
```

(정확한 `mockMvc` 인증/멀티파트 설정은 기존 이미지 분석 성공 테스트의 코드를 그대로 복사해서 mock 반환값만 위와 같이 바꾼다.)

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.api.AnalysisApiControllerTest"`
Expected: FAIL (컴파일 에러 — `AnalysisApiMapper`가 아직 옛 타입을 참조)

- [ ] **Step 3: `AnalysisApiMapper.java` 구현**

```java
package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AnalysisJobView;
import com.veritae.veritae_server.detection.AudioAnalysisResult;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.VideoDetectionResult;
import com.veritae.veritae_server.openapi.model.AnalysisJobAcceptedResponse;
import com.veritae.veritae_server.openapi.model.AnalysisJobResponse;
import com.veritae.veritae_server.openapi.model.AudioAnalysisResponse;
import com.veritae.veritae_server.openapi.model.Evidence;
import com.veritae.veritae_server.openapi.model.ImageAnalysisResponse;
import com.veritae.veritae_server.openapi.model.ScamEvidence;

import java.util.List;
import java.util.UUID;

public final class AnalysisApiMapper {

    private AnalysisApiMapper() {
    }

    public static ImageAnalysisResponse toResponse(ImageAnalysisResult result) {
        var openApiResult = new com.veritae.veritae_server.openapi.model.ImageDetectionResult(
                result.aiDetection().model(), result.aiDetection().score())
                .evidenceImage(result.aiDetection().evidenceImage());
        return new ImageAnalysisResponse(openApiResult)
                .scamDetection(toOpenApiScamDetection(result.scamDetection()));
    }

    public static AudioAnalysisResponse toAudioResponse(AudioAnalysisResult result) {
        var openApiResult = new com.veritae.veritae_server.openapi.model.AudioDetectionResult(
                result.aiDetection().model(), result.aiDetection().score(), toOpenApiEvidence(result.aiDetection().evidence()));
        return new AudioAnalysisResponse(openApiResult)
                .scamDetection(toOpenApiScamDetection(result.scamDetection()));
    }

    public static AnalysisJobAcceptedResponse toJobAcceptedResponse(UUID jobId) {
        return new AnalysisJobAcceptedResponse(jobId);
    }

    public static AnalysisJobResponse toJobResponse(AnalysisJobView view) {
        VideoAnalysisResult result = view.result();
        var aiDetection = result != null ? toOpenApiResult(result.aiDetection()) : null;
        var scamDetection = result != null ? toOpenApiScamDetection(result.scamDetection()) : null;
        var status = AnalysisJobResponse.StatusEnum.fromValue(view.status().name());
        return new AnalysisJobResponse(view.jobId(), status)
                .aiDetection(aiDetection)
                .scamDetection(scamDetection)
                .errorCode(view.errorCode())
                .errorMessage(view.errorMessage());
    }

    private static com.veritae.veritae_server.openapi.model.VideoDetectionResult toOpenApiResult(VideoDetectionResult result) {
        return new com.veritae.veritae_server.openapi.model.VideoDetectionResult(
                result.model(), result.score(), toOpenApiEvidence(result.evidence()))
                .evidenceImage(result.evidenceImage());
    }

    private static com.veritae.veritae_server.openapi.model.ScamDetectionResult toOpenApiScamDetection(
            com.veritae.veritae_server.detection.ScamDetectionResult scamDetection) {
        if (scamDetection == null) {
            return null;
        }
        List<ScamEvidence> evidence = scamDetection.evidence().stream()
                .map(e -> new ScamEvidence(e.sentence(), e.score()))
                .toList();
        return new com.veritae.veritae_server.openapi.model.ScamDetectionResult(
                scamDetection.model(), scamDetection.score(), evidence);
    }

    private static List<Evidence> toOpenApiEvidence(List<com.veritae.veritae_server.detection.Evidence> evidence) {
        return evidence.stream()
                .map(e -> new Evidence(e.title(), e.description(), e.tags(), e.startSec(), e.endSec()))
                .toList();
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.veritae.veritae_server.api.AnalysisApiControllerTest"`
Expected: PASS

- [ ] **Step 5: 전체 테스트 스위트 + 빌드 확인**

Run: `./gradlew build`
Expected: SUCCESS — 이 프로젝트의 모든 기존 테스트(auth/member/detection/analysis 전체) + 이번에 추가/수정된 테스트가 전부 통과해야 한다. 실패하면 Task 7~13에서 놓친 타입 변경(예: 다른 파일에서 옛 `ImageDetectionResult`/`AudioDetectionResult`/`VideoDetectionResult`를 여전히 서비스 반환 타입처럼 쓰는 곳)이 있는지 컴파일 에러 메시지를 따라간다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/api/AnalysisApiMapper.java \
        src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java
git commit -m "feat(fraud-risk): AnalysisApiMapper가 scamDetection을 응답에 포함하도록 변경"
```

---

## 이 계획에서 다루지 않는 것 (다음 세션)

- 실제 3060Ti 데스크탑에서 `text-extraction` conda env 설치 + 실행 검증 (PaddleOCR/faster-whisper/Lilju/kss가 실제로 깨끗이 설치되는지, `kss`가 실제 노이즈 낀 OCR/STT 출력에서 어떻게 동작하는지, 병렬화 후 음성 응답시간이 실제로 허용 범위인지) — 이 프로젝트의 모든 모델 통합이 그랬듯 실제 하드웨어에서 돌려보기 전까지는 버그가 있다고 가정할 것.
- Swagger 응답 예시(response example) 갱신 — `feedback_swagger_shared_schema_example_bug` 패턴 재발 여부 확인 필요(새 필드가 있는 스키마를 공유하는 응답에 명시적 예시가 없으면 Swagger가 모순된 예시를 자동 조합함).
- API 명세서 PDF 재생성(`scripts/generate_api_spec_pdf.py`).
