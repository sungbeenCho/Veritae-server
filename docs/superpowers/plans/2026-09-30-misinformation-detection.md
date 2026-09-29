# 가짜정보(허위정보) 판별 기능 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 이미지/음성/영상에서 뽑은 문장 중 한국어 위키백과 내용과 어긋나는 주장을 찾아 이유와 근거를 함께 보여주는 `misinformationDetection` 필드를 기존 분석 응답에 추가한다.

**Architecture:** detection-server(Python)에 위키 SQLite FTS5 인덱스 + 로컬 LLM(Ollama qwen3.5:4b) 판정 파이프라인을 새로 만들고, 기존 사기감지가 뽑은 문장을 재사용해 실행한다. GPU를 쓰는 모든 작업(SPAI/dfdc/Whisper 경로/e5/LLM)은 새 GPU 자원 큐를 거치게 해서 동시 실행으로 인한 GPU OOM을 막는다. Spring 쪽은 기존 `scamDetection` 패턴(레코드+공용 JSON 파서+3개 HTTP 클라이언트+매퍼)을 그대로 복제해 `misformationDetection`을 추가한다.

**Tech Stack:** FastAPI, pytest, SQLite FTS5, kiwipiepy(Apache-2.0), mwparserfromhell(MIT), Ollama(qwen3.5:4b), Spring Boot 4, JUnit5+AssertJ, OpenAPI(Gradle 코드젠).

**Spec:** `docs/superpowers/specs/2026-09-29-misinformation-detection-design.md` (커밋 `659d57e`) — 이 계획은 스펙을 그대로 따른다. 실행자는 두 문서를 함께 읽는다.

## Global Constraints

- LLM은 로컬(Ollama, GPU) 만 쓴다 — 유료 API 절대 금지(`feedback_no_llm`).
- 프롬프트는 스펙 §5에 적힌 문구를 그대로 쓴다 — 바꾸려면 반드시 회귀 테스트셋(Task 10)을 다시 돌려 통과를 확인한 뒤에만 바꾼다.
- `거짓(반박)으로 판정된 문장만` 응답에 넣는다 — 참/판단불가는 절대 노출하지 않는다.
- 가짜정보 파이프라인이 고장 나면(Ollama 무응답, 인덱스 파일 없음, 서브프로세스 실패, 타임아웃) 분석 요청 전체를 502로 실패시킨다 — 빈 결과로 조용히 넘어가지 않는다(사기감지와 동일 규칙).
- 문장 하나의 LLM 출력 형식이 잘못된 경우는 그 문장만 판단불가 처리하고 경고 로그 — 전체를 죽이지 않는다.
- GPU를 쓰는 서브프로세스/HTTP 호출은 전부 새 GPU 자원 큐를 거친다. 큐 단위는 `subprocess.run()` 한 번 또는 Ollama/e5 HTTP 호출 한 번 — FastAPI 요청 전체를 큐에 넣지 않는다(이미지의 OCR-only 경로는 큐를 타지 않는다).
- 새 필드/스키마에는 항상 명시적 Swagger 예시를 넣는다(`feedback_swagger_shared_schema_example_bug` — 예시 없으면 Swagger가 모순된 예시를 자동 조합함).
- 커밋 메시지 등 Claude가 작성하는 텍스트는 한국어로 쓴다(`feedback_korean_commit_messages`).
- 데스크탑에서만 확인 가능한 것(GPU 큐 대기시간, 인덱스 구축 시간, LLM 응답 속도, RAM 여유)은 이 계획에서 측정하지 않는다 — 설정값으로 빼두고 Task 10의 회귀 스크립트로 나중에 사용자가 데스크탑에서 실측·조정한다.

## Review Focus

- **위키에 아예 없는 주제의 문장**(예: 최근 사건, 특정 개인) — 검색이 엉뚱한 문서를 가져와도 억지로 반박 판정을 내리면 안 되고 판단불가로 물러나야 한다. 스펙 §2 실측(20건 중 3건)에서 이미 확인된 동작을 회귀로 고정한다(Task 10).
- **가짜정보 파이프라인 고장과 "텍스트 없음"의 혼동** — Ollama가 꺼져 있을 때도 `misinformationDetection: null`을 조용히 반환하면 "이상 없음"으로 오인된다. 502로 전체 실패해야 한다(Task 7).
- **LLM이 스키마를 벗어난 JSON을 줄 때**(드물지만 실측에서 사소한 문법 오류가 있었음) — 그 문장만 판단불가 처리하고 분석 전체는 살아있어야 한다(Task 5, 6).
- **GPU 자원 큐가 가득 찼을 때** — 타임아웃으로 조용히 죽는 대신 즉시 503과 이유를 반환해야 한다(Task 2).
- **기존 사기감지만 있고 가짜정보는 없던 과거 기록**(`misinfoRefutedCount` 컬럼이 null인 기록)을 리포트 집계에서 셀 때 — null을 0처럼 취급해 잘못 세면 안 되고, "검사 안 함"으로 제외돼야 한다(Task 14).

---

## PART 1 — veritae-detection-server (Python)

### Task 1: GPU 자원 큐

**Files:**
- Create: `app/services/gpu_queue.py`
- Test: `tests/test_gpu_queue.py`
- Modify: `app/config.py` (Settings 클래스 끝에 큐 설정 2개 추가)
- Test: `tests/test_config.py` (설정 기본값 테스트 추가)

**Interfaces:**
- Produces: `GpuQueueFullError(RuntimeError)`, `get_gpu_queue() -> GpuQueue`(캐시된 싱글턴, `get_settings()`와 동일 패턴), `GpuQueue.acquire(label: str)` — 컨텍스트 매니저. `with get_gpu_queue().acquire("spai"): ...`

- [ ] **Step 1: 큐 동작을 검증하는 실패 테스트 작성**

```python
# tests/test_gpu_queue.py
import threading
import time

import pytest

from app.services.gpu_queue import GpuQueue, GpuQueueFullError


def test_acquire_runs_one_at_a_time():
    queue = GpuQueue(max_concurrent=1, max_queue_depth=10)
    order = []

    def worker(n):
        with queue.acquire(f"job{n}"):
            order.append(("start", n))
            time.sleep(0.05)
            order.append(("end", n))

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(3)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    # max_concurrent=1이므로 어떤 job도 다른 job이 끝나기 전에 시작하면 안 된다.
    for i in range(0, len(order), 2):
        assert order[i][0] == "start"
        assert order[i + 1] == ("end", order[i][1])


def test_acquire_allows_up_to_max_concurrent():
    queue = GpuQueue(max_concurrent=2, max_queue_depth=10)
    concurrent = []
    max_seen = []
    lock = threading.Lock()

    def worker():
        with queue.acquire("job"):
            with lock:
                concurrent.append(1)
                max_seen.append(len(concurrent))
            time.sleep(0.05)
            with lock:
                concurrent.pop()

    threads = [threading.Thread(target=worker) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert max(max_seen) <= 2


def test_acquire_rejects_when_queue_full():
    queue = GpuQueue(max_concurrent=1, max_queue_depth=1)
    holder_entered = threading.Event()
    release_holder = threading.Event()

    def holder():
        with queue.acquire("holder"):
            holder_entered.set()
            release_holder.wait(timeout=2)

    def waiter():
        with queue.acquire("waiter"):
            pass

    t_holder = threading.Thread(target=holder)
    t_holder.start()
    holder_entered.wait(timeout=2)

    t_waiter = threading.Thread(target=waiter)
    t_waiter.start()
    time.sleep(0.05)  # waiter가 대기열에 들어갈 시간을 준다

    with pytest.raises(GpuQueueFullError):
        with queue.acquire("overflow"):
            pass

    release_holder.set()
    t_holder.join()
    t_waiter.join()
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `pytest tests/test_gpu_queue.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.services.gpu_queue'`

- [ ] **Step 3: 최소 구현 작성**

```python
# app/services/gpu_queue.py
"""GPU를 쓰는 서브프로세스/HTTP 호출을 한 번에 max_concurrent개만 돌게 하는 자원 큐.
subprocess.run() 호출 하나 또는 Ollama/e5 HTTP 호출 하나 단위로 잠근다 - FastAPI 요청
전체를 잠그지 않는다(docs/superpowers/specs/2026-09-29-misinformation-detection-design.md §6).
대기열에 상한을 둬서, 너무 많이 쌓이면 타임아웃으로 조용히 죽는 대신 즉시 거절한다.
나중에 배포 트래픽 관리 작업에서 이 큐를 그대로 확장해 쓸 수 있게 대기시간을 로그로 남긴다.
"""
from __future__ import annotations

import logging
import threading
import time
from contextlib import contextmanager
from functools import lru_cache

from app.config import get_settings

logger = logging.getLogger(__name__)


class GpuQueueFullError(RuntimeError):
    pass


class GpuQueue:
    def __init__(self, max_concurrent: int, max_queue_depth: int):
        self._semaphore = threading.Semaphore(max_concurrent)
        self._max_queue_depth = max_queue_depth
        self._lock = threading.Lock()
        self._waiting = 0

    @contextmanager
    def acquire(self, label: str):
        with self._lock:
            if self._waiting >= self._max_queue_depth:
                logger.warning("gpu_queue_full label=%s waiting=%d", label, self._waiting)
                raise GpuQueueFullError(
                    f"GPU 자원 큐가 가득 찼습니다(대기 {self._max_queue_depth}건 초과)"
                )
            self._waiting += 1
        wait_start = time.monotonic()
        try:
            self._semaphore.acquire()
        finally:
            with self._lock:
                self._waiting -= 1
        wait_seconds = time.monotonic() - wait_start
        logger.info("gpu_queue_acquired label=%s wait_seconds=%.3f", label, wait_seconds)
        run_start = time.monotonic()
        try:
            yield
        finally:
            self._semaphore.release()
            logger.info(
                "gpu_queue_released label=%s run_seconds=%.3f",
                label,
                time.monotonic() - run_start,
            )


@lru_cache
def get_gpu_queue() -> GpuQueue:
    settings = get_settings()
    return GpuQueue(
        max_concurrent=settings.gpu_queue_max_concurrent,
        max_queue_depth=settings.gpu_queue_max_depth,
    )
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `pytest tests/test_gpu_queue.py -v`
Expected: PASS (3 tests)

- [ ] **Step 5: `app/config.py`에 설정 2개 추가**

`app/config.py`의 사기 위험도 분석(fraud-risk) 섹션(`text_extraction_work_dir.mkdir(...)` 다음 줄) 뒤에 추가:

```python
        # --- GPU 자원 큐 설정. GPU를 쓰는 서브프로세스/HTTP 호출(SPAI/dfdc/Whisper 경로/e5/LLM)이
        # 한 번에 max_concurrent개만 동시 실행되게 한다(2026-09-29 결정, 가짜정보탐지 설계 §6).
        # 지금은 GPU가 한 장이라 1이 기본값 - 나중에 자원이 늘어나면 이 값만 올리면 된다.
        self.gpu_queue_max_concurrent = int(os.environ.get("GPU_QUEUE_MAX_CONCURRENT", "1"))
        self.gpu_queue_max_depth = int(os.environ.get("GPU_QUEUE_MAX_DEPTH", "10"))
```

- [ ] **Step 6: 설정 기본값 테스트 추가**

`tests/test_config.py`에 추가:

```python
def test_gpu_queue_settings_have_sane_defaults(monkeypatch):
    monkeypatch.setenv("SPAI_REPO_DIR", "C:/fake/spai")
    monkeypatch.setenv("ANTIDEEPFAKE_REPO_DIR", "C:/fake/antideepfake")
    monkeypatch.setenv("DFDC_REPO_DIR", "C:/fake/dfdc")
    monkeypatch.delenv("GPU_QUEUE_MAX_CONCURRENT", raising=False)
    monkeypatch.delenv("GPU_QUEUE_MAX_DEPTH", raising=False)

    settings = Settings()

    assert settings.gpu_queue_max_concurrent == 1
    assert settings.gpu_queue_max_depth == 10
```

- [ ] **Step 7: 전체 테스트 실행 및 커밋**

Run: `pytest tests/test_config.py tests/test_gpu_queue.py -v`
Expected: 전부 PASS

```bash
git add app/services/gpu_queue.py app/config.py tests/test_gpu_queue.py tests/test_config.py
git commit -m "feat(gpu): GPU 자원 큐 추가 - 동시 GPU 작업을 설정값 개수로 제한

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: 기존 SPAI/dfdc 호출에 GPU 큐 연결 + 큐 가득 참 → 503

**Files:**
- Modify: `app/services/spai_runner.py:74-84` (subprocess.run 호출부를 큐로 감싼다)
- Modify: `app/services/dfdc_runner.py:51-60` (동일)
- Modify: `app/main.py` (GpuQueueFullError → 503 전역 핸들러)
- Test: `tests/test_spai_runner.py`, `tests/test_dfdc_runner.py`(큐 acquire 호출 확인)
- Test: `tests/test_image_router.py`, `tests/test_video_router.py`(큐 가득 참 → 503 라우터 테스트)

**Interfaces:**
- Consumes: `get_gpu_queue()`, `GpuQueueFullError` (Task 1)

- [ ] **Step 1: SPAI가 큐를 쓰는지 확인하는 실패 테스트**

```python
# tests/test_spai_runner.py 에 추가
from app.services import gpu_queue as gpu_queue_module


def test_run_spai_inference_uses_gpu_queue(monkeypatch, tmp_path):
    calls = []

    class FakeQueue:
        def acquire(self, label):
            calls.append(label)
            from contextlib import contextmanager

            @contextmanager
            def cm():
                yield

            return cm()

    monkeypatch.setattr(gpu_queue_module, "get_gpu_queue", lambda: FakeQueue())
    # (기존 테스트처럼 get_settings/subprocess.run을 mock하는 나머지 셋업은 이 파일의
    # 다른 테스트와 동일한 패턴을 그대로 따른다 - settings.work_dir=tmp_path,
    # subprocess.run이 성공 CSV를 쓰도록 monkeypatch)
    ...
    from app.services.spai_runner import run_spai_inference

    run_spai_inference(b"fake", "test.jpg")

    assert calls == ["spai"]
```

> 이 파일의 기존 SPAI mock 셋업(“...” 부분)은 `tests/test_spai_runner.py`의 다른 테스트가 이미 쓰고 있는 `monkeypatch.setattr(spai_runner, "get_settings", ...)` + `monkeypatch.setattr(subprocess, "run", ...)` 패턴을 그대로 복사해서 채운다 - 이 계획에서 그 파일 전체를 다시 옮겨 적지 않는다.

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `pytest tests/test_spai_runner.py::test_run_spai_inference_uses_gpu_queue -v`
Expected: FAIL (큐를 아직 안 씀 - calls가 빈 리스트)

- [ ] **Step 3: `spai_runner.py`에 큐 연결**

`app/services/spai_runner.py` 상단 import에 추가:

```python
from app.services.gpu_queue import get_gpu_queue
```

`run_spai_inference`의 `try:` 블록 안, `result = subprocess.run(...)` 호출을 아래로 교체:

```python
    try:
        with get_gpu_queue().acquire("spai"):
            result = subprocess.run(
                command,
                cwd=settings.spai_repo_dir,
                capture_output=True,
                text=True,
                timeout=settings.spai_timeout_seconds,
            )
```

- [ ] **Step 4: dfdc도 동일하게 연결**

`app/services/dfdc_runner.py` 상단에 `from app.services.gpu_queue import get_gpu_queue` 추가. `run_dfdc_inference`의 `result = subprocess.run(...)` 호출(51-60줄)을 `with get_gpu_queue().acquire("dfdc"):`로 감싼다(들여쓰기만 한 단계 추가, 나머지 인자는 동일).

- [ ] **Step 5: 테스트 통과 확인**

Run: `pytest tests/test_spai_runner.py tests/test_dfdc_runner.py -v`
Expected: PASS

- [ ] **Step 6: 큐 가득 참을 503으로 바꾸는 전역 핸들러**

`app/main.py`를 아래로 교체:

```python
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.routers import audio, image, video
from app.services.gpu_queue import GpuQueueFullError

app = FastAPI(title="Veritae Detection Server")

app.include_router(image.router)
app.include_router(audio.router)
app.include_router(video.router)


@app.exception_handler(GpuQueueFullError)
async def gpu_queue_full_handler(request: Request, exc: GpuQueueFullError) -> JSONResponse:
    return JSONResponse(status_code=503, content={"detail": str(exc)})


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
```

- [ ] **Step 7: 라우터가 큐 가득참을 503으로 돌려주는지 확인하는 테스트**

`tests/test_image_router.py`에 추가:

```python
from app.services.gpu_queue import GpuQueueFullError


def test_process_image_returns_503_when_gpu_queue_full(monkeypatch):
    def raise_full(data, filename):
        raise GpuQueueFullError("GPU 자원 큐가 가득 찼습니다")

    monkeypatch.setattr(image_router, "run_spai_inference", raise_full)
    monkeypatch.setattr(
        image_router, "run_scam_inference_image", lambda data, filename: ScamResult(None, [], [])
    )

    response = client.post(
        "/process/image",
        files={"file": ("test.jpg", io.BytesIO(b"fake-image-bytes"), "image/jpeg")},
    )

    assert response.status_code == 503
```

(`ScamResult(None, [], [])`는 Task 3에서 `sentences` 필드가 추가된 뒤의 시그니처다 - Task 3을 먼저 병합했다는 전제. 순서상 이 스텝은 Task 3 이후에 실행해도 무방하다.)

같은 패턴으로 `tests/test_video_router.py`에 `test_process_video_returns_503_when_gpu_queue_full`(dfdc 쪽 mock에서 raise)을 추가한다.

- [ ] **Step 8: 테스트 실행 및 커밋**

Run: `pytest tests/ -v -k "gpu_queue or 503"`
Expected: PASS

```bash
git add app/services/spai_runner.py app/services/dfdc_runner.py app/main.py tests/test_spai_runner.py tests/test_dfdc_runner.py tests/test_image_router.py tests/test_video_router.py
git commit -m "feat(gpu): SPAI/dfdc 호출을 GPU 큐로 감싸고 큐 가득참을 503으로 응답

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: 사기감지 결과에 `sentences` 추가 + GPU 큐 연결(stt/video만)

**Files:**
- Modify: `scripts/scam_infer.py` (main()이 쓰는 결과 dict에 `"sentences": sentences` 추가)
- Modify: `app/services/scam_runner.py` (`ScamResult`에 `sentences` 필드, `_parse_result`가 읽음, `_run_scam_infer`/`_run_scam_infer_video`가 GPU 큐 사용 — 단 `mode == "ocr"`일 때는 큐를 타지 않음)
- Test: `tests/test_scam_runner.py` (기존 테스트에 `sentences` 검증 추가 + 큐 사용 여부 테스트)

**Interfaces:**
- Produces: `ScamResult(score, evidence, sentences)` — `sentences: list[str]`, 텍스트가 없으면 `[]`.

- [ ] **Step 1: `scam_infer.py`에 `sentences` 추가**

`scripts/scam_infer.py`의 `main()` 함수에서, 문장이 없을 때 쓰는 줄과 있을 때 쓰는 줄 둘 다 고친다:

```python
    if not sentences:
        args.output.write_text(json.dumps({"score": None, "evidence": [], "sentences": []}), encoding="utf-8")
        return

    scores = score_sentences(sentences, args.lilju_model_id)
    result = {
        "score": aggregate(scores),
        "evidence": build_evidence(sentences, scores),
        "sentences": sentences,
    }
    args.output.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
```

- [ ] **Step 2: `ScamResult`/`_parse_result`에 `sentences` 추가하는 실패 테스트**

`tests/test_scam_runner.py`의 `_write_result_json`과 그 사용처를 고친다:

```python
def _write_result_json(output_file: Path, score, evidence=None, sentences=None) -> None:
    output_file.write_text(
        json.dumps({"score": score, "evidence": evidence or [], "sentences": sentences or []}),
        encoding="utf-8",
    )
```

`test_run_scam_inference_image_returns_score_and_evidence`에 아래 두 줄 추가:

```python
    def fake_run(command, **kwargs):
        output_file = Path(command[command.index("--output") + 1])
        _write_result_json(
            output_file, 0.82,
            [{"sentence": "계좌번호를 알려주세요", "score": 0.95}],
            sentences=["계좌번호를 알려주세요", "지금 바로 입금하세요"],
        )
        return MagicMock(returncode=0, stderr="")
```

그리고 어서션에 추가:

```python
    assert result.sentences == ["계좌번호를 알려주세요", "지금 바로 입금하세요"]
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `pytest tests/test_scam_runner.py -v`
Expected: FAIL (`ScamResult`에 `sentences` 속성이 없음)

- [ ] **Step 4: `scam_runner.py` 구현**

`app/services/scam_runner.py` 상단에 추가:

```python
from app.services.gpu_queue import get_gpu_queue
```

`ScamResult` 클래스를 아래로 교체:

```python
class ScamResult:
    def __init__(self, score: float | None, evidence: list[dict], sentences: list[str]):
        self.score = score
        self.evidence = evidence
        self.sentences = sentences
```

`_parse_result`를 아래로 교체:

```python
def _parse_result(output_file: Path) -> ScamResult:
    try:
        data = json.loads(output_file.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError, OSError) as e:
        raise ScamInferenceError(f"사기감지 output JSON을 읽거나 파싱할 수 없습니다: {output_file}") from e
    return ScamResult(
        score=data.get("score"), evidence=data.get("evidence", []), sentences=data.get("sentences", [])
    )
```

`_run_scam_infer`의 `subprocess.run(...)` 호출부(53-62줄)를 아래로 교체 — `mode == "ocr"`일 때만 큐를 건너뛴다:

```python
    try:
        if mode == "ocr":
            result = subprocess.run(
                command,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=settings.text_extraction_timeout_seconds,
                env=_SUBPROCESS_ENV,
            )
        else:
            with get_gpu_queue().acquire(f"scam_infer:{mode}"):
                result = subprocess.run(
                    command,
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    timeout=settings.text_extraction_timeout_seconds,
                    env=_SUBPROCESS_ENV,
                )
```

`_run_scam_infer_video`의 `subprocess.run(...)` 호출부(103-111줄)도 항상 큐를 타도록 동일하게 `with get_gpu_queue().acquire("scam_infer:video"):`로 감싼다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `pytest tests/test_scam_runner.py -v`
Expected: PASS

- [ ] **Step 6: 큐 사용 여부(ocr는 안 타고 stt는 탐)를 검증하는 테스트 추가**

```python
# tests/test_scam_runner.py 에 추가
from app.services import gpu_queue as gpu_queue_module


def _fake_queue(calls):
    class FakeQueue:
        def acquire(self, label):
            calls.append(label)
            from contextlib import contextmanager

            @contextmanager
            def cm():
                yield

            return cm()

    return FakeQueue()


@patch("app.services.scam_runner.subprocess.run")
@patch("app.services.scam_runner.get_settings")
def test_run_scam_inference_image_does_not_use_gpu_queue(mock_get_settings, mock_run, tmp_path, monkeypatch):
    mock_get_settings.return_value = _scam_settings(tmp_path)
    calls = []
    monkeypatch.setattr(gpu_queue_module, "get_gpu_queue", lambda: _fake_queue(calls))

    def fake_run(command, **kwargs):
        output_file = Path(command[command.index("--output") + 1])
        _write_result_json(output_file, None)
        return MagicMock(returncode=0, stderr="")

    mock_run.side_effect = fake_run

    run_scam_inference_image(b"fake-image-bytes", "test.jpg")

    assert calls == []


@patch("app.services.scam_runner.subprocess.run")
@patch("app.services.scam_runner.get_settings")
def test_run_scam_inference_audio_uses_gpu_queue(mock_get_settings, mock_run, tmp_path, monkeypatch):
    mock_get_settings.return_value = _scam_settings(tmp_path)
    calls = []
    monkeypatch.setattr(gpu_queue_module, "get_gpu_queue", lambda: _fake_queue(calls))

    def fake_run(command, **kwargs):
        output_file = Path(command[command.index("--output") + 1])
        _write_result_json(output_file, None)
        return MagicMock(returncode=0, stderr="")

    mock_run.side_effect = fake_run

    run_scam_inference_audio(b"fake-audio-bytes", "test.wav")

    assert calls == ["scam_infer:stt"]
```

`app/services/scam_runner.py`는 `get_gpu_queue`를 `from app.services.gpu_queue import get_gpu_queue`로 모듈 안에 바인딩했으므로, 위 테스트의 `monkeypatch.setattr(gpu_queue_module, "get_gpu_queue", ...)`가 먹히려면 `scam_runner.py`에서 `get_gpu_queue()`를 직접 호출하는 대신 모듈 레퍼런스로 호출해야 한다 — 이미 `from app.services.gpu_queue import get_gpu_queue`로 이름을 가져왔으므로 `scam_runner.get_gpu_queue`를 patch 대상으로 삼는다. 따라서 위 두 테스트의 `monkeypatch.setattr`을 `gpu_queue_module`이 아니라 `scam_runner` 모듈에 하도록 고친다:

```python
    monkeypatch.setattr("app.services.scam_runner.get_gpu_queue", lambda: _fake_queue(calls))
```

(Task 2의 SPAI/dfdc 테스트에도 동일한 수정이 필요하다 — `app.services.spai_runner.get_gpu_queue`, `app.services.dfdc_runner.get_gpu_queue`를 patch 대상으로 쓴다. Task 2 Step 1의 예시 코드를 이 방식으로 맞춰서 작성한다.)

- [ ] **Step 7: 테스트 실행 및 커밋**

Run: `pytest tests/test_scam_runner.py tests/test_spai_runner.py tests/test_dfdc_runner.py -v`
Expected: 전부 PASS

```bash
git add scripts/scam_infer.py app/services/scam_runner.py tests/test_scam_runner.py
git commit -m "feat(scam): 사기감지 결과에 문장 목록을 추가하고 음성/영상 경로만 GPU 큐 연결

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: 한국어 텍스트/위키 인덱스 공용 모듈 (`scripts/wiki_index.py`)

**Files:**
- Create: `scripts/__init__.py` (빈 파일 - pytest가 `scripts.wiki_index`를 임포트할 수 있게)
- Create: `scripts/wiki_index.py`
- Test: `tests/test_wiki_index.py`
- Modify: `requirements-dev.txt` (kiwipiepy, mwparserfromhell 추가 - 둘 다 GPU/torch 불필요, 순수 텍스트 처리라 개발 환경에서 바로 테스트 가능)

**Interfaces:**
- Produces:
  - `strip_wiki_markup(wikitext: str) -> str`
  - `split_into_chunks(text: str, target_chars: int = 200) -> list[str]`
  - `wiki_url(title: str) -> str`
  - `create_index(db_path: Path, snapshot: str) -> sqlite3.Connection`
  - `add_chunk(conn: sqlite3.Connection, title: str, text: str, keywords: str) -> None`
  - `get_snapshot(conn: sqlite3.Connection) -> str`
  - `search(conn: sqlite3.Connection, keywords: str, limit: int = 50) -> list[tuple[str, str]]` — `(title, text)` 목록, BM25 순.

- [ ] **Step 1: requirements-dev.txt에 두 패키지 추가**

```
-r requirements.txt
pytest>=8.0
httpx>=0.27
mwparserfromhell>=0.6
kiwipiepy>=0.20
```

Run: `pip install -r requirements-dev.txt`

- [ ] **Step 2: 마크업 제거 + 조각 분할 실패 테스트**

```python
# tests/test_wiki_index.py
import sqlite3

import pytest

from scripts.wiki_index import (
    add_chunk,
    create_index,
    get_snapshot,
    search,
    split_into_chunks,
    strip_wiki_markup,
    wiki_url,
)


def test_strip_wiki_markup_removes_common_noise():
    wikitext = (
        "세종대왕은 조선의 [[제4대]] [[왕]]이다.\n"
        "[[파일:King.jpg|섬네일|세종대왕 초상화]]\n"
        "훈민정음을 창제했다.<ref>세종실록</ref>\n"
        "{| class=\"wikitable\"\n|-\n| 즉위 || 1418년\n|}\n"
    )

    plain = strip_wiki_markup(wikitext)

    assert "세종대왕은 조선의 제4대 왕이다." in plain
    assert "훈민정음을 창제했다." in plain
    assert "세종실록" not in plain
    assert "wikitable" not in plain
    assert "섬네일" not in plain


def test_split_into_chunks_groups_sentences_under_target_size():
    text = "문장 하나. 문장 둘. 문장 셋. " * 20

    chunks = split_into_chunks(text, target_chars=50)

    assert len(chunks) > 1
    assert all(len(c) <= 70 for c in chunks)  # target보다 살짝 넘는 것까진 허용(문장 단위로만 자름)
    assert "".join(chunks).replace(" ", "") == text.replace(" ", "")[: len("".join(chunks).replace(" ", ""))]


def test_wiki_url_encodes_spaces_as_underscores():
    assert wiki_url("선풍기 사망설") == "https://ko.wikipedia.org/wiki/선풍기_사망설"


def test_create_index_and_search_roundtrip(tmp_path):
    db_path = tmp_path / "wiki.sqlite3"
    conn = create_index(db_path, snapshot="2026-09-01")
    add_chunk(conn, "선풍기 사망설", "선풍기 사망설이란 밀폐된 방에서 선풍기를 켜놓고 자면 사망한다는 미신이다.", "선풍기 사망설 미신")
    add_chunk(conn, "에베레스트산", "에베레스트산은 세계에서 가장 높은 산이다.", "에베레스트산 세계 가장 높다 산")
    conn.commit()

    results = search(conn, "선풍기 사망설", limit=5)

    assert results[0][0] == "선풍기 사망설"
    assert get_snapshot(conn) == "2026-09-01"


def test_search_returns_empty_list_when_nothing_matches(tmp_path):
    conn = create_index(tmp_path / "wiki.sqlite3", snapshot="2026-09-01")
    add_chunk(conn, "에베레스트산", "에베레스트산은 세계에서 가장 높은 산이다.", "에베레스트산 세계 가장 높다 산")
    conn.commit()

    assert search(conn, "복권 당첨", limit=5) == []
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `pytest tests/test_wiki_index.py -v`
Expected: FAIL (`ModuleNotFoundError`)

- [ ] **Step 4: 구현**

```python
# scripts/wiki_index.py
"""위키 조각 인덱스 공용 모듈. build_wiki_index.py(구축)와 misinfo_infer.py(조회) 둘 다
이 모듈을 쓴다 - 같은 디렉터리 안 스크립트라 파이썬이 자동으로 sys.path에 잡아준다.
RAM에 통째로 올리지 않고 디스크의 SQLite FTS5 파일 하나로 검색한다
(docs/superpowers/specs/2026-09-29-misinformation-detection-design.md §4).
"""
from __future__ import annotations

import re
import sqlite3
import urllib.parse
from pathlib import Path

import mwparserfromhell

_DROP_TAGS = {"ref", "gallery", "math", "timeline", "score"}
_DROP_LINE = re.compile(r"^\s*(분류:|파일:|File:|Category:|thumb\||섬네일\|)", re.IGNORECASE)
_SENT_SPLIT = re.compile(r"(?<=[.!?])\s+")


def strip_wiki_markup(wikitext: str) -> str:
    code = mwparserfromhell.parse(wikitext)
    for tag in code.filter_tags(recursive=True):
        if str(tag.tag).lower() in _DROP_TAGS:
            try:
                code.remove(tag)
            except ValueError:
                pass
    plain = code.strip_code(normalize=True, collapse=True)
    lines = [line for line in plain.split("\n") if line.strip() and not _DROP_LINE.match(line.strip())]
    return "\n".join(lines)


def split_into_chunks(text: str, target_chars: int = 200) -> list[str]:
    sentences: list[str] = []
    for line in text.split("\n"):
        line = line.strip()
        if not line:
            continue
        sentences.extend(s.strip() for s in _SENT_SPLIT.split(line) if s.strip())

    chunks: list[str] = []
    current = ""
    for sentence in sentences:
        if current and len(current) + len(sentence) > target_chars:
            chunks.append(current)
            current = sentence
        else:
            current = f"{current} {sentence}".strip()
    if current:
        chunks.append(current)
    return chunks


def wiki_url(title: str) -> str:
    return f"https://ko.wikipedia.org/wiki/{title.replace(' ', '_')}"


def create_index(db_path: Path, snapshot: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path)
    conn.execute(
        "CREATE VIRTUAL TABLE IF NOT EXISTS chunks USING fts5(title, text, keywords)"
    )
    conn.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)")
    conn.execute(
        "INSERT INTO meta (key, value) VALUES ('snapshot', ?) "
        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        (snapshot,),
    )
    conn.commit()
    return conn


def add_chunk(conn: sqlite3.Connection, title: str, text: str, keywords: str) -> None:
    conn.execute("INSERT INTO chunks (title, text, keywords) VALUES (?, ?, ?)", (title, text, keywords))


def get_snapshot(conn: sqlite3.Connection) -> str:
    row = conn.execute("SELECT value FROM meta WHERE key = 'snapshot'").fetchone()
    return row[0] if row else ""


def search(conn: sqlite3.Connection, keywords: str, limit: int = 50) -> list[tuple[str, str]]:
    if not keywords.strip():
        return []
    rows = conn.execute(
        "SELECT title, text FROM chunks WHERE keywords MATCH ? ORDER BY rank LIMIT ?",
        (keywords, limit),
    ).fetchall()
    return [(row[0], row[1]) for row in rows]
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `pytest tests/test_wiki_index.py -v`
Expected: PASS (5개)

- [ ] **Step 6: 커밋**

```bash
git add scripts/__init__.py scripts/wiki_index.py tests/test_wiki_index.py requirements-dev.txt
git commit -m "feat(wiki): 위키 조각 인덱스 공용 모듈(SQLite FTS5) 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: 위키 덤프 → 인덱스 구축 CLI (`scripts/build_wiki_index.py`)

**Files:**
- Create: `scripts/build_wiki_index.py`
- Modify: `app/config.py` (인덱스 경로 설정 추가)
- Modify: `app/services/gpu_queue.py`는 손대지 않음 — 이 스크립트는 GPU를 쓰지 않는다(순수 텍스트).

**Interfaces:**
- Consumes: `scripts/wiki_index.py`의 `strip_wiki_markup`, `split_into_chunks`, `create_index`, `add_chunk`
- Produces: CLI. 실행: `python build_wiki_index.py --dump <bz2 경로> --output <db 경로> --snapshot <YYYY-MM-DD>`. 이 스크립트 자체는 실제 위키 덤프(1.4GB)가 있어야 끝까지 도니 **pytest로 단위 테스트하지 않는다** — `scripts/scam_infer.py`가 EasyOCR/Whisper/Lilju 없이는 못 도는 것과 같은 이유로 이 레포의 기존 관례다(`tests/`에 `test_scam_infer.py`가 없는 것 참고). 핵심 로직(마크업 제거/조각 분할/인덱스 CRUD)은 이미 Task 4에서 전부 테스트됨 — 이 파일은 그 함수들을 XML 스트리밍 파싱과 엮는 얇은 CLI일 뿐이다.

- [ ] **Step 1: `app/config.py`에 인덱스 경로 설정 추가**

Task 1 Step 5에서 추가한 GPU 큐 설정 블록 바로 뒤에 추가:

```python
        # --- 가짜정보탐지(misinformation) 설정. 위키 인덱스는 build_wiki_index.py로 미리
        # 만들어둔 SQLite FTS5 파일(RAM에 안 올림) - 자동 갱신 없음, 필요할 때 재구축(2026-09-29 결정).
        self.wiki_index_path = Path(os.environ.get("WIKI_INDEX_PATH", "./data/wiki_index.sqlite3"))
```

(파일 상단에 `from pathlib import Path`가 이미 있으므로 추가 import는 불필요 — `app/config.py` 기존 import 확인.)

- [ ] **Step 2: 구현**

```python
# scripts/build_wiki_index.py
"""kowiki-latest-pages-articles.xml.bz2 -> SQLite FTS5 인덱스.
데스크탑에서 한 번(또는 원할 때 다시) 실행하는 오프라인 배치 스크립트 - GPU를 쓰지 않는다.
사용: python build_wiki_index.py --dump <bz2 경로> --output <db 경로> --snapshot <YYYY-MM-DD> [--limit N]
"""
import argparse
import bz2
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from kiwipiepy import Kiwi

from wiki_index import add_chunk, create_index, split_into_chunks, strip_wiki_markup


def iter_pages(dump_path: Path, limit: int):
    n = 0
    with bz2.open(dump_path, "rb") as f:
        for _, elem in ET.iterparse(f, events=("end",)):
            tag = elem.tag
            if not tag.endswith("}page"):
                continue
            ns_prefix = tag[: tag.index("}") + 1]
            ns = elem.findtext(f"{ns_prefix}ns")
            redirect = elem.find(f"{ns_prefix}redirect")
            if ns == "0" and redirect is None:
                title = elem.findtext(f"{ns_prefix}title")
                text = elem.findtext(f"{ns_prefix}revision/{ns_prefix}text") or ""
                yield title, text
                n += 1
                if limit and n >= limit:
                    elem.clear()
                    return
            elem.clear()


def extract_keywords(kiwi: Kiwi, text: str) -> str:
    # 조사/어미를 떼고 의미 있는 형태소(명사/동사/형용사)만 남겨 검색 정확도를 높인다
    # ("만리장성은"으로 검색해도 "만리장성" 문서를 찾도록 - 2026-09-29 실측으로 확인된 필요성).
    keep_tags = {"NNG", "NNP", "VV", "VA", "SL", "SN"}
    tokens = [t.form for t in kiwi.tokenize(text) if t.tag in keep_tags]
    return " ".join(tokens)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dump", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--snapshot", required=True)
    parser.add_argument("--limit", type=int, default=0, help="0=전체(디버그/테스트용 제한)")
    args = parser.parse_args()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        args.output.unlink()

    kiwi = Kiwi()
    conn = create_index(args.output, snapshot=args.snapshot)

    pages = chunks_total = 0
    t0 = time.time()
    for title, wikitext in iter_pages(args.dump, args.limit):
        pages += 1
        plain = strip_wiki_markup(wikitext)
        for chunk in split_into_chunks(plain):
            keywords = extract_keywords(kiwi, chunk)
            if keywords:
                add_chunk(conn, title, chunk, keywords)
                chunks_total += 1
        if pages % 5000 == 0:
            conn.commit()
            elapsed = time.time() - t0
            print(f"pages={pages} chunks={chunks_total} elapsed={elapsed:.0f}s", file=sys.stderr, flush=True)

    conn.commit()
    conn.close()
    print(f"완료: pages={pages} chunks={chunks_total} elapsed={time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
```

- [ ] **Step 3: 문법 오류 없이 임포트되는지만 확인(실제 덤프 없이)**

Run: `python -c "import ast; ast.parse(open('scripts/build_wiki_index.py', encoding='utf-8').read())"`
Expected: 예외 없이 종료(문법 검증만 - 실제 실행은 데스크탑에서 덤프로 함, README Task 9에 안내)

- [ ] **Step 4: 커밋**

```bash
git add scripts/build_wiki_index.py app/config.py
git commit -m "feat(wiki): 위키 덤프에서 SQLite 인덱스를 만드는 구축 스크립트 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: LLM 프롬프트/응답 검증 로직 (`scripts/misinfo_lib.py`)

**Files:**
- Create: `scripts/misinfo_lib.py`
- Test: `tests/test_misinfo_lib.py`

**Interfaces:**
- Produces:
  - `PROMPT_TEMPLATE: str` (스펙 §5 고정 프롬프트)
  - `build_prompt(sentence: str, evidence_chunks: list[tuple[str, str]]) -> str`
  - `parse_llm_response(raw_response: str) -> dict | None` — `{"label": "지지"|"반박"|"판단불가", "reason": str}` 또는 형식이 잘못됐으면 `None`.
  - `build_claim(sentence: str, verdict: dict, evidence_chunks: list[tuple[str, str]]) -> dict` — `{"sentence", "reason", "evidence": [{"title","text","url"}]}`

- [ ] **Step 1: 실패 테스트 작성**

```python
# tests/test_misinfo_lib.py
from scripts.misinfo_lib import build_claim, build_prompt, parse_llm_response


def test_build_prompt_includes_sentence_and_evidence_titles():
    prompt = build_prompt(
        "선풍기를 틀고 자면 사망한다.",
        [("선풍기 사망설", "선풍기 사망설은 근거 없는 미신이다.")],
    )

    assert "선풍기를 틀고 자면 사망한다." in prompt
    assert "선풍기 사망설은 근거 없는 미신이다." in prompt
    assert "지지" in prompt and "반박" in prompt and "판단불가" in prompt


def test_parse_llm_response_accepts_valid_json():
    raw = '{"label": "반박", "reason": "근거 문단이 주장을 부정합니다."}'

    parsed = parse_llm_response(raw)

    assert parsed == {"label": "반박", "reason": "근거 문단이 주장을 부정합니다."}


def test_parse_llm_response_rejects_invalid_label():
    raw = '{"label": "모르겠음", "reason": "..."}'

    assert parse_llm_response(raw) is None


def test_parse_llm_response_rejects_missing_reason():
    raw = '{"label": "반박"}'

    assert parse_llm_response(raw) is None


def test_parse_llm_response_rejects_malformed_json():
    raw = "이건 JSON이 아니다"

    assert parse_llm_response(raw) is None


def test_build_claim_assembles_evidence_with_urls():
    verdict = {"label": "반박", "reason": "근거 문단이 주장을 부정합니다."}

    claim = build_claim(
        "선풍기를 틀고 자면 사망한다.",
        verdict,
        [("선풍기 사망설", "선풍기 사망설은 근거 없는 미신이다.")],
    )

    assert claim == {
        "sentence": "선풍기를 틀고 자면 사망한다.",
        "reason": "근거 문단이 주장을 부정합니다.",
        "evidence": [
            {
                "title": "선풍기 사망설",
                "text": "선풍기 사망설은 근거 없는 미신이다.",
                "url": "https://ko.wikipedia.org/wiki/선풍기_사망설",
            }
        ],
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `pytest tests/test_misinfo_lib.py -v`
Expected: FAIL (`ModuleNotFoundError`)

- [ ] **Step 3: 구현**

```python
# scripts/misinfo_lib.py
"""LLM 프롬프트 조립과 응답 검증 - 순수 함수만 모아서 실제 모델/네트워크 없이 테스트한다.
프롬프트 문구는 2026-09-29 실측(위키 검색 포함 20건, 16/20)에 쓴 것과 동일하게 고정한다
(docs/superpowers/specs/2026-09-29-misinformation-detection-design.md §5) - 바꾸려면
tests/regression의 회귀 테스트셋을 반드시 다시 돌려야 한다.
"""
from __future__ import annotations

import json

from wiki_index import wiki_url

VALID_LABELS = {"지지", "반박", "판단불가"}

PROMPT_TEMPLATE = """너는 사실 검증 도우미다. 아래 [근거 문단]들은 위키백과에서 자동으로 찾아온 조각이며, 주장과 관련 없는 조각이 섞여 있을 수 있다. [근거 문단]에 적힌 내용만 보고 [주장]을 판정하라. 네가 원래 알고 있는 지식은 쓰지 마라.
- 지지: 근거 문단이 주장이 참이라고 말한다
- 반박: 근거 문단이 주장이 거짓이라고 말한다
- 판단불가: 근거 문단이 주장에 대해 참/거짓을 말하지 않는다

[근거 문단]
{premise}

[주장]
{hypothesis}

label과 한 문장짜리 reason을 JSON으로 답하라."""


def build_prompt(sentence: str, evidence_chunks: list[tuple[str, str]]) -> str:
    premise = "\n".join(f"- ({title}) {text}" for title, text in evidence_chunks)
    return PROMPT_TEMPLATE.format(premise=premise, hypothesis=sentence)


def parse_llm_response(raw_response: str) -> dict | None:
    try:
        data = json.loads(raw_response)
    except (json.JSONDecodeError, TypeError):
        return None
    label = data.get("label")
    reason = data.get("reason")
    if label not in VALID_LABELS or not isinstance(reason, str) or not reason.strip():
        return None
    return {"label": label, "reason": reason}


def build_claim(sentence: str, verdict: dict, evidence_chunks: list[tuple[str, str]]) -> dict:
    return {
        "sentence": sentence,
        "reason": verdict["reason"],
        "evidence": [
            {"title": title, "text": text, "url": wiki_url(title)} for title, text in evidence_chunks
        ],
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `pytest tests/test_misinfo_lib.py -v`
Expected: PASS (6개)

- [ ] **Step 5: 커밋**

```bash
git add scripts/misinfo_lib.py tests/test_misinfo_lib.py
git commit -m "feat(misinfo): LLM 프롬프트 조립과 응답 검증 순수 함수 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: 가짜정보 판정 CLI (`scripts/misinfo_infer.py`)

**Files:**
- Create: `scripts/misinfo_infer.py`
- Modify: `app/config.py` (Ollama/판정 설정 추가)

**Interfaces:**
- Consumes: `scripts/wiki_index.py`(search), `scripts/misinfo_lib.py`(build_prompt/parse_llm_response/build_claim), `scripts/build_wiki_index.py`의 `extract_keywords`(같은 Kiwi 키워드 추출 로직 재사용 - 아래 Step 1에서 `wiki_index.py`로 옮겨 중복 제거)
- Produces: CLI. `python misinfo_infer.py --sentences <문장 목록 json 경로> --output <결과 json 경로> --wiki-index <db 경로> --ollama-url <url> --ollama-model <model> --evidence-count 5`. **이 파일도 Task 5와 같은 이유로 pytest 단위 테스트 대상이 아니다** — e5 임베딩(torch)과 Ollama HTTP 호출(실제 서버 필요)이 얽혀 있어 dev 환경에서 못 돈다. 로직은 이미 Task 6에서 테스트된 순수 함수로 최대한 뽑아뒀다. 실제 검증은 Task 10의 회귀 스크립트로 데스크탑에서 한다.

- [ ] **Step 1: `extract_keywords`를 `wiki_index.py`로 옮겨 중복 제거**

`scripts/build_wiki_index.py`의 `extract_keywords` 함수를 잘라내 `scripts/wiki_index.py`로 옮기고(함수 시그니처 `extract_keywords(kiwi: "Kiwi", text: str) -> str` 그대로), `wiki_index.py` 상단에 `from kiwipiepy import Kiwi`를 타입힌트용으로 추가한다(TYPE_CHECKING 블록 없이 그냥 import해도 kiwipiepy가 requirements-dev에 이미 있어 문제없음). `build_wiki_index.py`는 `from wiki_index import add_chunk, create_index, extract_keywords, split_into_chunks, strip_wiki_markup`로 import를 바꾸고 자체 정의는 지운다.

Run: `pytest tests/test_wiki_index.py tests/test_misinfo_lib.py -v`
Expected: 기존 테스트 전부 PASS(동작 변화 없음, 위치만 이동)

- [ ] **Step 2: `app/config.py`에 판정 설정 추가**

Task 5 Step 1에서 추가한 `wiki_index_path` 다음 줄에 추가:

```python
        self.misinfo_script = Path(
            os.environ.get(
                "MISINFO_SCRIPT",
                str(Path(__file__).resolve().parent.parent / "scripts" / "misinfo_infer.py"),
            )
        )
        self.ollama_url = os.environ.get("OLLAMA_URL", "http://localhost:11434")
        self.ollama_model = os.environ.get("OLLAMA_MODEL", "qwen3.5:4b")
        self.misinfo_evidence_chunk_count = int(os.environ.get("MISINFO_EVIDENCE_CHUNK_COUNT", "5"))
        self.misinfo_timeout_seconds = int(os.environ.get("MISINFO_TIMEOUT_SECONDS", "300"))
```

- [ ] **Step 3: 구현**

```python
# scripts/misinfo_infer.py
"""문장 목록 -> 위키 검색 -> e5 재정렬 -> LLM 판정 -> 반박된 문장만 결과로.
text-extraction conda env(kiwipiepy, mwparserfromhell, torch, transformers 설치됨)에서
실행되어야 한다. veritae-detection-server(FastAPI)는 이 스크립트를 subprocess로 호출하고
--output 경로의 JSON만 읽는다 - scam_infer.py와 동일한 패턴.

docs(veritae-server 레포): docs/superpowers/specs/2026-09-29-misinformation-detection-design.md
"""
import argparse
import json
import urllib.request
from pathlib import Path

from kiwipiepy import Kiwi

from misinfo_lib import build_claim, build_prompt, parse_llm_response
from wiki_index import extract_keywords, search

OLLAMA_SCHEMA = {
    "type": "object",
    "properties": {
        "label": {"type": "string", "enum": ["지지", "반박", "판단불가"]},
        "reason": {"type": "string"},
    },
    "required": ["label", "reason"],
}


def embed(texts: list[str]):
    import torch
    from transformers import AutoModel, AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained("intfloat/multilingual-e5-small")
    model = AutoModel.from_pretrained("intfloat/multilingual-e5-small")
    model.eval()
    batch = tokenizer(texts, padding=True, truncation=True, max_length=256, return_tensors="pt")
    with torch.no_grad():
        hidden = model(**batch).last_hidden_state
    mask = batch["attention_mask"].unsqueeze(-1).float()
    vectors = (hidden * mask).sum(1) / mask.sum(1)
    return torch.nn.functional.normalize(vectors, dim=-1)


def rerank(sentence: str, candidates: list[tuple[str, str]], top_k: int) -> list[tuple[str, str]]:
    if not candidates:
        return []
    query_vec = embed(["query: " + sentence])
    passage_vecs = embed([f"passage: {title} {text}" for title, text in candidates])
    scores = (passage_vecs @ query_vec.T).squeeze(-1)
    top_indices = scores.argsort(descending=True)[:top_k].tolist()
    return [candidates[i] for i in top_indices]


def ask_ollama(ollama_url: str, model: str, prompt: str) -> str:
    body = {
        "model": model,
        "prompt": prompt,
        "format": OLLAMA_SCHEMA,
        "stream": False,
        "think": False,
        "options": {"temperature": 0},
    }
    req = urllib.request.Request(
        f"{ollama_url}/api/generate",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=180) as resp:
        return json.loads(resp.read())["response"]


def judge_sentence(kiwi: Kiwi, conn, sentence: str, ollama_url: str, model: str, evidence_count: int) -> dict | None:
    keywords = extract_keywords(kiwi, sentence)
    candidates = search(conn, keywords, limit=50) if keywords else []
    if not candidates:
        return None

    top_chunks = rerank(sentence, candidates, top_k=evidence_count)
    prompt = build_prompt(sentence, top_chunks)
    raw_response = ask_ollama(ollama_url, model, prompt)
    verdict = parse_llm_response(raw_response)
    if verdict is None:
        return None  # 형식 오류 - 이 문장은 판단불가로 취급(§10), 결과에 포함하지 않는다
    if verdict["label"] != "반박":
        return None
    return build_claim(sentence, verdict, top_chunks)


def main() -> None:
    import sqlite3

    parser = argparse.ArgumentParser()
    parser.add_argument("--sentences", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--wiki-index", required=True, type=Path)
    parser.add_argument("--ollama-url", required=True)
    parser.add_argument("--ollama-model", required=True)
    parser.add_argument("--evidence-count", type=int, default=5)
    args = parser.parse_args()

    sentences = json.loads(args.sentences.read_text(encoding="utf-8"))

    conn = sqlite3.connect(args.wiki_index)
    snapshot_row = conn.execute("SELECT value FROM meta WHERE key = 'snapshot'").fetchone()
    snapshot = snapshot_row[0] if snapshot_row else ""

    kiwi = Kiwi()
    claims = []
    for sentence in sentences:
        claim = judge_sentence(kiwi, conn, sentence, args.ollama_url, args.ollama_model, args.evidence_count)
        if claim is not None:
            claims.append(claim)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    result = {"model": args.ollama_model, "wiki_snapshot": snapshot, "claims": claims}
    args.output.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: 문법만 검증**

Run: `python -c "import ast; ast.parse(open('scripts/misinfo_infer.py', encoding='utf-8').read())"`
Expected: 예외 없음

- [ ] **Step 5: 커밋**

```bash
git add scripts/misinfo_infer.py scripts/build_wiki_index.py scripts/wiki_index.py app/config.py
git commit -m "feat(misinfo): 가짜정보 판정 CLI 추가(위키 검색+e5 재정렬+Ollama 판정)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: `app/services/misinfo_runner.py` (서브프로세스 래퍼) + GPU 큐

**Files:**
- Create: `app/services/misinfo_runner.py`
- Test: `tests/test_misinfo_runner.py`

**Interfaces:**
- Consumes: `get_settings()`, `get_gpu_queue()`
- Produces: `MisinfoInferenceError(RuntimeError)`, `MisinfoResult(model: str, wiki_snapshot: str, claims: list[dict])`, `run_misinfo_inference(sentences: list[str]) -> MisinfoResult`

- [ ] **Step 1: 실패 테스트 작성 (scam_runner의 테스트 패턴을 그대로 따른다)**

```python
# tests/test_misinfo_runner.py
import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from app.services.misinfo_runner import MisinfoInferenceError, run_misinfo_inference


def _misinfo_settings(tmp_path) -> MagicMock:
    settings = MagicMock()
    settings.misinfo_script = Path("/fake/misinfo_infer.py")
    settings.text_extraction_python = "python"
    settings.text_extraction_work_dir = tmp_path
    settings.wiki_index_path = Path("/fake/wiki_index.sqlite3")
    settings.ollama_url = "http://localhost:11434"
    settings.ollama_model = "qwen3.5:4b"
    settings.misinfo_evidence_chunk_count = 5
    settings.misinfo_timeout_seconds = 300
    return settings


def _write_result_json(output_file: Path, claims=None, wiki_snapshot="2026-09-01") -> None:
    output_file.write_text(
        json.dumps({"model": "qwen3.5:4b", "wiki_snapshot": wiki_snapshot, "claims": claims or []}),
        encoding="utf-8",
    )


@patch("app.services.misinfo_runner.subprocess.run")
@patch("app.services.misinfo_runner.get_settings")
def test_run_misinfo_inference_returns_claims(mock_get_settings, mock_run, tmp_path):
    mock_get_settings.return_value = _misinfo_settings(tmp_path)

    def fake_run(command, **kwargs):
        output_file = Path(command[command.index("--output") + 1])
        _write_result_json(
            output_file,
            claims=[{"sentence": "선풍기를 틀고 자면 사망한다.", "reason": "미신이다.", "evidence": []}],
        )
        return MagicMock(returncode=0, stderr="")

    mock_run.side_effect = fake_run

    result = run_misinfo_inference(["선풍기를 틀고 자면 사망한다."])

    assert result.wiki_snapshot == "2026-09-01"
    assert result.claims == [{"sentence": "선풍기를 틀고 자면 사망한다.", "reason": "미신이다.", "evidence": []}]
    called_command = mock_run.call_args.args[0]
    assert "--sentences" in called_command


@patch("app.services.misinfo_runner.subprocess.run")
@patch("app.services.misinfo_runner.get_settings")
def test_run_misinfo_inference_raises_on_nonzero_exit(mock_get_settings, mock_run, tmp_path):
    mock_get_settings.return_value = _misinfo_settings(tmp_path)
    mock_run.return_value = MagicMock(returncode=1, stderr="boom")

    with pytest.raises(MisinfoInferenceError):
        run_misinfo_inference(["아무 문장"])


@patch("app.services.misinfo_runner.subprocess.run")
@patch("app.services.misinfo_runner.get_settings")
def test_run_misinfo_inference_raises_on_timeout(mock_get_settings, mock_run, tmp_path):
    import subprocess

    mock_get_settings.return_value = _misinfo_settings(tmp_path)
    mock_run.side_effect = subprocess.TimeoutExpired(cmd="misinfo_infer.py", timeout=300)

    with pytest.raises(MisinfoInferenceError):
        run_misinfo_inference(["아무 문장"])


@patch("app.services.misinfo_runner.subprocess.run")
@patch("app.services.misinfo_runner.get_settings")
def test_run_misinfo_inference_uses_gpu_queue(mock_get_settings, mock_run, tmp_path, monkeypatch):
    mock_get_settings.return_value = _misinfo_settings(tmp_path)
    calls = []

    class FakeQueue:
        def acquire(self, label):
            calls.append(label)
            from contextlib import contextmanager

            @contextmanager
            def cm():
                yield

            return cm()

    monkeypatch.setattr("app.services.misinfo_runner.get_gpu_queue", lambda: FakeQueue())

    def fake_run(command, **kwargs):
        output_file = Path(command[command.index("--output") + 1])
        _write_result_json(output_file)
        return MagicMock(returncode=0, stderr="")

    mock_run.side_effect = fake_run

    run_misinfo_inference(["아무 문장"])

    assert calls == ["misinfo_infer"]
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `pytest tests/test_misinfo_runner.py -v`
Expected: FAIL (`ModuleNotFoundError`)

- [ ] **Step 3: 구현 (scam_runner.py의 `_run_scam_infer` 구조를 그대로 따름)**

```python
# app/services/misinfo_runner.py
import json
import shutil
import subprocess
import uuid
from pathlib import Path

from app.config import get_settings
from app.services.gpu_queue import get_gpu_queue


class MisinfoInferenceError(RuntimeError):
    pass


class MisinfoResult:
    def __init__(self, model: str, wiki_snapshot: str, claims: list[dict]):
        self.model = model
        self.wiki_snapshot = wiki_snapshot
        self.claims = claims


def run_misinfo_inference(sentences: list[str]) -> MisinfoResult:
    settings = get_settings()
    job_dir = settings.text_extraction_work_dir / uuid.uuid4().hex
    job_dir.mkdir(parents=True, exist_ok=True)
    sentences_file = job_dir / "sentences.json"
    output_file = job_dir / "result.json"
    sentences_file.write_text(json.dumps(sentences, ensure_ascii=False), encoding="utf-8")

    command = [
        settings.text_extraction_python,
        str(settings.misinfo_script),
        "--sentences", str(sentences_file),
        "--output", str(output_file),
        "--wiki-index", str(settings.wiki_index_path),
        "--ollama-url", settings.ollama_url,
        "--ollama-model", settings.ollama_model,
        "--evidence-count", str(settings.misinfo_evidence_chunk_count),
    ]

    try:
        with get_gpu_queue().acquire("misinfo_infer"):
            result = subprocess.run(
                command,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=settings.misinfo_timeout_seconds,
            )

        if result.returncode != 0:
            raise MisinfoInferenceError(f"가짜정보 판정 실패: {result.stderr[-2000:]}")

        if not output_file.exists():
            raise MisinfoInferenceError(f"expected output JSON not found: {output_file}")

        return _parse_result(output_file)
    except subprocess.TimeoutExpired as e:
        raise MisinfoInferenceError(
            f"가짜정보 판정이 {settings.misinfo_timeout_seconds}초 안에 끝나지 않았습니다"
        ) from e
    finally:
        shutil.rmtree(job_dir, ignore_errors=True)


def _parse_result(output_file: Path) -> MisinfoResult:
    try:
        data = json.loads(output_file.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError, OSError) as e:
        raise MisinfoInferenceError(f"가짜정보 판정 output JSON을 읽거나 파싱할 수 없습니다: {output_file}") from e
    return MisinfoResult(
        model=data.get("model", ""), wiki_snapshot=data.get("wiki_snapshot", ""), claims=data.get("claims", [])
    )
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `pytest tests/test_misinfo_runner.py -v`
Expected: PASS (4개)

- [ ] **Step 5: 커밋**

```bash
git add app/services/misinfo_runner.py tests/test_misinfo_runner.py
git commit -m "feat(misinfo): 가짜정보 판정 서브프로세스 래퍼 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: 라우터 3곳에 가짜정보탐지 연결 + 스키마 추가

**Files:**
- Modify: `app/schemas.py` (`WikiEvidence`, `MisinformationClaim`, `MisinformationDetectionResult` 추가, 3개 Response에 필드 추가)
- Modify: `app/routers/image.py`, `app/routers/audio.py`, `app/routers/video.py`
- Test: `tests/test_image_router.py`, `tests/test_audio_router.py`, `tests/test_video_router.py`

**Interfaces:**
- Consumes: `run_misinfo_inference`, `MisinfoInferenceError` (Task 8)
- Produces: 세 응답 모두 `misinformation_detection: MisinformationDetectionResult | None`

- [ ] **Step 1: 실패 테스트 (이미지 기준 - 오디오/비디오는 같은 패턴으로 복제)**

`tests/test_image_router.py`에 추가:

```python
from app.services.scam_runner import ScamResult
from app.services.misinfo_runner import MisinfoInferenceError


def test_process_image_includes_misinformation_detection_when_refuted(monkeypatch):
    monkeypatch.setattr(image_router, "run_spai_inference", lambda data, filename: SpaiResult(0.1, None))
    monkeypatch.setattr(
        image_router,
        "run_scam_inference_image",
        lambda data, filename: ScamResult(None, [], ["선풍기를 틀고 자면 사망한다."]),
    )
    monkeypatch.setattr(
        image_router,
        "run_misinfo_inference",
        lambda sentences: type(
            "R", (), {"model": "qwen3.5:4b", "wiki_snapshot": "2026-09-01",
                      "claims": [{"sentence": sentences[0], "reason": "미신이다.", "evidence": []}]}
        )(),
    )

    response = client.post(
        "/process/image",
        files={"file": ("test.jpg", io.BytesIO(b"fake-image-bytes"), "image/jpeg")},
    )

    assert response.status_code == 200
    assert response.json()["misinformation_detection"] == {
        "model": "qwen3.5:4b",
        "wiki_snapshot": "2026-09-01",
        "claims": [{"sentence": "선풍기를 틀고 자면 사망한다.", "reason": "미신이다.", "evidence": []}],
    }


def test_process_image_omits_misinformation_detection_when_no_sentences(monkeypatch):
    monkeypatch.setattr(image_router, "run_spai_inference", lambda data, filename: SpaiResult(0.1, None))
    monkeypatch.setattr(
        image_router, "run_scam_inference_image", lambda data, filename: ScamResult(None, [], [])
    )
    called = []
    monkeypatch.setattr(
        image_router, "run_misinfo_inference", lambda sentences: called.append(sentences)
    )

    response = client.post(
        "/process/image",
        files={"file": ("test.jpg", io.BytesIO(b"fake-image-bytes"), "image/jpeg")},
    )

    assert response.json()["misinformation_detection"] is None
    assert called == []  # 문장이 없으면 가짜정보 판정 자체를 호출하지 않는다


def test_process_image_returns_502_when_misinfo_inference_fails(monkeypatch):
    monkeypatch.setattr(image_router, "run_spai_inference", lambda data, filename: SpaiResult(0.1, None))
    monkeypatch.setattr(
        image_router,
        "run_scam_inference_image",
        lambda data, filename: ScamResult(None, [], ["아무 문장"]),
    )

    def raise_error(sentences):
        raise MisinfoInferenceError("boom")

    monkeypatch.setattr(image_router, "run_misinfo_inference", raise_error)

    response = client.post(
        "/process/image",
        files={"file": ("test.jpg", io.BytesIO(b"fake-image-bytes"), "image/jpeg")},
    )

    assert response.status_code == 502
```

같은 세 테스트를 `test_audio_router.py`(`run_scam_inference_audio` 기준), `test_video_router.py`(`run_scam_inference_video` 기준)에도 동일한 패턴으로 추가한다 — 파일마다 이미 있는 성공/502 테스트 바로 아래에 붙인다.

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `pytest tests/test_image_router.py tests/test_audio_router.py tests/test_video_router.py -v`
Expected: FAIL (`misinformation_detection` 키가 응답에 없음, `run_misinfo_inference`가 라우터에 없음)

- [ ] **Step 3: `app/schemas.py`에 타입 추가**

`ScamDetectionResult` 클래스 다음에 추가:

```python
class WikiEvidence(BaseModel):
    title: str
    text: str
    url: str


class MisinformationClaim(BaseModel):
    sentence: str
    reason: str
    evidence: list[WikiEvidence]


class MisinformationDetectionResult(BaseModel):
    model: str
    wiki_snapshot: str
    claims: list[MisinformationClaim]
```

`ImageAnalysisResponse`, `AudioAnalysisResponse`, `VideoAnalysisResponse` 세 클래스 각각에 한 줄씩 추가:

```python
    misinformation_detection: MisinformationDetectionResult | None = None
```

- [ ] **Step 4: `image.py` 라우터 수정**

`app/routers/image.py` 상단 import를 아래로 교체:

```python
from app.schemas import (
    AIDetectionResult,
    ImageAnalysisResponse,
    MisinformationDetectionResult,
    ScamDetectionResult,
    ScamEvidence,
)
from app.services.misinfo_runner import MisinfoInferenceError, run_misinfo_inference
from app.services.scam_runner import ScamInferenceError, run_scam_inference_image
from app.services.spai_runner import SpaiInferenceError, run_spai_inference
```

`scam_detection = (...)` 블록과 `return ImageAnalysisResponse(...)` 사이에 추가:

```python
    misinformation_detection = None
    if scam_result.sentences:
        try:
            misinfo_result = run_misinfo_inference(scam_result.sentences)
        except MisinfoInferenceError as e:
            logger.exception("가짜정보 판정 파이프라인 실패")
            raise HTTPException(status_code=502, detail="가짜정보 판정 처리 중 오류가 발생했습니다.") from e
        misinformation_detection = MisinformationDetectionResult(
            model=misinfo_result.model,
            wiki_snapshot=misinfo_result.wiki_snapshot,
            claims=misinfo_result.claims,
        )
```

`return` 문을 아래로 교체:

```python
    return ImageAnalysisResponse(
        ai_detection=AIDetectionResult(
            model="spai", score=ai_result.score, evidence_image=ai_result.evidence_image
        ),
        scam_detection=scam_detection,
        misinformation_detection=misinformation_detection,
    )
```

- [ ] **Step 5: `audio.py`, `video.py`에 동일 패턴 적용**

`app/routers/audio.py`: import에 `MisinformationDetectionResult` 추가, `from app.services.misinfo_runner import MisinfoInferenceError, run_misinfo_inference` 추가. `scam_detection = (...)` 다음, `return AudioAnalysisResponse(...)` 앞에 image.py와 동일한 `misinformation_detection = None` 블록 삽입(변수명만 재사용). `return`에 `misinformation_detection=misinformation_detection` 추가.

`app/routers/video.py`: 동일. 단 `scam_result`가 `ScamInferenceError`로 이미 502를 던진 뒤 코드이므로, 가짜정보 판정 블록은 `scam_detection = (...)` 계산 다음, `ai_detection = None` 블록 이전 아무 곳(순서 무관)에 넣어도 된다. `return VideoAnalysisResponse(...)`에 `misinformation_detection=misinformation_detection` 추가.

- [ ] **Step 6: 테스트 통과 확인**

Run: `pytest tests/test_image_router.py tests/test_audio_router.py tests/test_video_router.py -v`
Expected: 전부 PASS

- [ ] **Step 7: 회귀 확인 겸 전체 스위트 실행**

Run: `pytest -v`
Expected: 전부 PASS

- [ ] **Step 8: 커밋**

```bash
git add app/schemas.py app/routers/image.py app/routers/audio.py app/routers/video.py tests/test_image_router.py tests/test_audio_router.py tests/test_video_router.py
git commit -m "feat(misinfo): 세 라우터에 가짜정보탐지 연결, 문장 없으면 생략, 실패 시 502

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 10: 회귀 테스트셋 (`tests/regression/`)

**Files:**
- Create: `tests/regression/cases_basic.json` (2026-09-29 실측 36건)
- Create: `tests/regression/cases_hard.json` (2026-09-29 실측 어려운 16건)
- Create: `tests/regression/run_regression.py`
- Create: `tests/regression/README.md`

**Interfaces:**
- Produces: 데스크탑에서 실행하는 독립 스크립트. `python run_regression.py --wiki-index <db> --ollama-url http://localhost:11434 --ollama-model qwen3.5:4b`. pytest 스위트에는 포함하지 않는다(실제 Ollama/위키 인덱스가 있어야 돎 - Task 5/7과 같은 이유).

- [ ] **Step 1: 오늘 세션에서 실측에 쓴 36건 세트를 JSON으로 저장**

```json
// tests/regression/cases_basic.json
[
  {"expected": "entailment", "category": "단순함의", "sentence": "세종대왕은 훈민정음을 창제했다."},
  {"expected": "entailment", "category": "단순함의", "sentence": "에베레스트산은 세계에서 가장 높은 산이다."},
  {"expected": "entailment", "category": "단순함의", "sentence": "대한민국의 수도는 서울이다."},
  {"expected": "entailment", "category": "단순함의", "sentence": "이순신은 한산도 대첩에서 승리했다."},
  {"expected": "entailment", "category": "단순함의", "sentence": "물의 끓는점은 1기압에서 섭씨 100도이다."},
  {"expected": "entailment", "category": "단순함의", "sentence": "커피에는 카페인이 들어 있다."},
  {"expected": "contradiction", "category": "거짓주장", "sentence": "만리장성은 우주에서 맨눈으로 보인다."},
  {"expected": "contradiction", "category": "거짓주장", "sentence": "인간은 뇌의 10퍼센트만 사용한다."},
  {"expected": "contradiction", "category": "거짓주장", "sentence": "선풍기를 틀고 자면 사망한다."},
  {"expected": "contradiction", "category": "거짓주장", "sentence": "백신은 자폐증을 유발한다."},
  {"expected": "contradiction", "category": "거짓주장", "sentence": "부산은 대한민국의 수도이다."},
  {"expected": "contradiction", "category": "거짓주장", "sentence": "아폴로 11호의 달 착륙은 조작되었다."},
  {"expected": "contradiction", "category": "거짓주장", "sentence": "지구는 평평하다."},
  {"expected": "contradiction", "category": "거짓주장", "sentence": "에펠탑은 영국 런던에 있다."},
  {"expected": "contradiction", "category": "거짓주장", "sentence": "세종대왕은 고려의 왕이었다."},
  {"expected": "contradiction", "category": "거짓주장", "sentence": "한글은 조선 세조 때 만들어졌다."},
  {"expected": "contradiction", "category": "거짓주장", "sentence": "태양은 지구 주위를 돈다."},
  {"expected": "neutral", "category": "위키에없음", "sentence": "우리 동네 편의점은 다음 주에 문을 닫는다."},
  {"expected": "neutral", "category": "위키에없음", "sentence": "김철수 씨는 어제 복권에 당첨되었다."},
  {"expected": "neutral", "category": "위키에없음", "sentence": "다음 주 월요일에 서울에 비가 온다."}
]
```

> 이 20건은 2026-09-29 "위키 검색까지 포함한 전체 흐름" 실측에 쓴 세트다(스펙 §2, 16/20 통과, 위험 오답 0). 36건 세트(인용+부정/단순부정/인용+긍정 포함, mDeBERTa 비교에 쓴 것)는 대화 기록에서 그대로 옮겨적지 않고, 실행자가 이 파일을 만들 때 세션 스크래치패드가 남아있으면 `test_llm_nli.py`의 `CASES`(36건)를 그대로 이식하고, 없으면 위 20건만으로 우선 진행한 뒤 나머지는 별도 태스크로 넘긴다 — **이 계획에서 지어내지 않는다(No Placeholders 원칙상, 실제로 검증된 적 없는 문장을 새로 만들어 넣지 않는다).**

`tests/regression/cases_hard.json`도 동일하게, 오늘 실측한 "처음 보는 어려운 16건"(이중부정/위키체 긴 문장/전언+미확정 함정 포함, qwen3.5:4b 16/16)을 옮긴다. 위와 같은 사유로 원 데이터(`hard_cases.py`의 `HARD_CASES`)가 있으면 그대로 이식한다.

- [ ] **Step 2: 실행 스크립트 작성**

```python
# tests/regression/run_regression.py
"""회귀 테스트셋을 실제 위키 인덱스+Ollama로 돌려 정확도/시간/GPU 큐 대기시간을 잰다.
pytest가 아니라 독립 스크립트다 - 실제 모델과 인덱스가 있는 데스크탑에서만 돈다.
프롬프트(scripts/misinfo_lib.py의 PROMPT_TEMPLATE)나 설정값을 바꿀 때마다 이걸로 재확인한다.

사용: python run_regression.py --wiki-index <db경로> --ollama-url http://localhost:11434 --ollama-model qwen3.5:4b
"""
import argparse
import json
import sqlite3
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "scripts"))
from kiwipiepy import Kiwi  # noqa: E402
from misinfo_infer import judge_sentence  # noqa: E402

HERE = Path(__file__).parent


def load_cases(name: str) -> list[dict]:
    return json.loads((HERE / name).read_text(encoding="utf-8"))


def run_group(name: str, cases: list[dict], kiwi, conn, ollama_url: str, model: str, evidence_count: int) -> None:
    correct = 0
    dangerous = 0
    start = time.time()
    for case in cases:
        t0 = time.time()
        claim = judge_sentence(kiwi, conn, case["sentence"], ollama_url, model, evidence_count)
        got = "contradiction" if claim is not None else "not_contradiction"
        expected_is_contradiction = case["expected"] == "contradiction"
        ok = (got == "contradiction") == expected_is_contradiction
        danger = expected_is_contradiction is False and got == "contradiction"
        correct += int(ok)
        dangerous += int(danger)
        tag = "OK" if ok else ("DANGER" if danger else "MISS")
        print(f"[{tag}] {case['category']:10s} expected={case['expected']:13s} got={got:16s} "
              f"({time.time() - t0:.1f}s) | {case['sentence']}", flush=True)
    elapsed = time.time() - start
    print(f"\n##### {name}: {correct}/{len(cases)} 정확, 위험 오답 {dangerous}건, "
          f"총 {elapsed:.0f}s(문장당 {elapsed / len(cases):.1f}s)\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--wiki-index", required=True, type=Path)
    parser.add_argument("--ollama-url", required=True)
    parser.add_argument("--ollama-model", required=True)
    parser.add_argument("--evidence-count", type=int, default=5)
    args = parser.parse_args()

    conn = sqlite3.connect(args.wiki_index)
    kiwi = Kiwi()

    run_group("cases_basic", load_cases("cases_basic.json"), kiwi, conn, args.ollama_url, args.ollama_model, args.evidence_count)
    run_group("cases_hard", load_cases("cases_hard.json"), kiwi, conn, args.ollama_url, args.ollama_model, args.evidence_count)


if __name__ == "__main__":
    main()
```

> `judge_sentence`는 Task 7에서 "반박이 아니면 None"을 반환하도록 만들어졌으므로, 이 스크립트는 "반박 판정이 나왔는가"만으로 채점한다 — 지지/판단불가를 구분하고 싶으면 Task 7에서 `judge_sentence`가 verdict 자체도 함께 반환하도록 나중에 확장할 수 있다(지금 스코프에서는 "거짓만 보여준다"는 요구사항과 정확히 대응하는 채점 방식이라 충분하다).

- [ ] **Step 3: 사용법 문서**

```markdown
# tests/regression/README.md

가짜정보탐지 정확도/속도 회귀 테스트셋. pytest가 아니라 **데스크탑에서 수동으로** 돌린다 -
실제 위키 인덱스(build_wiki_index.py로 미리 구축)와 Ollama가 켜져 있어야 한다.

## 언제 돌리나

- 프롬프트(`scripts/misinfo_lib.py`의 `PROMPT_TEMPLATE`)를 바꿀 때
- `MISINFO_EVIDENCE_CHUNK_COUNT`, Ollama 모델 이름 등 설정값을 바꿀 때
- 위키 인덱스를 재구축한 뒤

## 실행

```powershell
conda activate text-extraction
cd C:\ai\veritae-detection-server\tests\regression
python run_regression.py --wiki-index C:\ai\veritae-detection-server\data\wiki_index.sqlite3 --ollama-url http://localhost:11434 --ollama-model qwen3.5:4b
```

## 판단 기준

- `cases_basic.json`(20건), `cases_hard.json`(16건) 각각 정확도와 "위험 오답"(참인데 거짓으로 판정) 건수를 출력한다.
- 위험 오답이 0이 아니면 배포/변경 전에 원인을 확인한다 - 2026-09-29 실측 기준 기대치는 두 세트 모두 위험 오답 0건이다.
```

- [ ] **Step 4: 커밋**

```bash
git add tests/regression/
git commit -m "test(misinfo): 데스크탑용 회귀 테스트셋과 실행 스크립트 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 11: README 갱신 (Ollama, 위키 인덱스, 가짜정보탐지 환경변수)

**Files:**
- Modify: `README.md` (기존 "사기 위험도 분석" 섹션 뒤에 새 섹션 추가)

- [ ] **Step 1: 기존 섹션 스타일을 따라 새 섹션 추가**

`README.md`의 "사기 위험도 분석(text-extraction) 설정" 섹션이 끝나는 지점(파일 끝) 뒤에 추가:

```markdown
---

## 가짜정보(허위정보) 판별 설정

이 서버는 텍스트에서 뽑은 문장 중 한국어 위키백과 내용과 어긋나는 주장을 찾아준다. 판정은 로컬 LLM(Ollama)으로 하고, 검색은 미리 만들어둔 위키 인덱스(SQLite 파일)로 한다 - 둘 다 이 데스크탑에서만 돌고 외부로 아무것도 나가지 않는다.

### 1. Ollama 설치 및 모델 다운로드

```powershell
winget install --id Ollama.Ollama -e
```

**설치 프로그램이 시작 프로그램에 자동 등록한다 - 데스크탑에서는 이게 의도된 동작이다**(항상 켜둬야 하는 서버이므로). 설치 후 새 PowerShell 창에서:

```powershell
ollama pull qwen3.5:4b
```

### 2. `text-extraction` 환경에 패키지 2개 추가

기존 사기 위험도 분석 설정에서 만든 `text-extraction` conda 환경을 그대로 재사용한다(새 환경 안 만듦).

```powershell
conda activate text-extraction
pip install kiwipiepy mwparserfromhell
```

### 3. 위키 인덱스 구축 (최초 1회, 이후 원할 때 재실행)

한국어 위키백과 전체 덤프(약 1.4GB)를 받아서 인덱스를 만든다. 몇십 분 정도 걸릴 수 있다.

```powershell
cd C:\ai\veritae-detection-server
Invoke-WebRequest -Uri "https://dumps.wikimedia.org/kowiki/latest/kowiki-latest-pages-articles.xml.bz2" -OutFile "C:\ai\kowiki-latest-pages-articles.xml.bz2"
conda activate text-extraction
cd scripts
python build_wiki_index.py --dump C:\ai\kowiki-latest-pages-articles.xml.bz2 --output C:\ai\veritae-detection-server\data\wiki_index.sqlite3 --snapshot 2026-09-01
```

`--snapshot`은 그날 날짜(YYYY-MM-DD)로 적으면 된다 - 응답의 `wikiSnapshot` 필드로 그대로 나간다.

### 4. 환경변수 설정

```powershell
$env:WIKI_INDEX_PATH = "C:\ai\veritae-detection-server\data\wiki_index.sqlite3"
$env:OLLAMA_URL = "http://localhost:11434"   # 기본값과 동일, 보통 안 바꿔도 됨
$env:OLLAMA_MODEL = "qwen3.5:4b"             # 기본값과 동일
```

### 5. 서버 재시작 및 확인

```powershell
conda activate detection-api
cd C:\ai\veritae-detection-server
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

가짜정보탐지가 실제로 잘 되는지, 프롬프트나 설정을 바꾼 뒤에도 정확도가 떨어지지 않았는지는 `tests/regression/README.md`의 회귀 테스트셋으로 확인한다.

### GPU 자원 큐

SPAI/dfdc/사기감지(음성·영상)/가짜정보탐지가 전부 같은 GPU를 쓰기 때문에, 동시에 여러 요청이 오면 한 번에 하나씩만 실제로 GPU를 쓰고 나머지는 순서를 기다린다(`GPU_QUEUE_MAX_CONCURRENT`, 기본 1). 대기 중인 요청이 너무 많이 쌓이면(`GPU_QUEUE_MAX_DEPTH`, 기본 10) 새 요청은 503으로 즉시 거절된다. 필요하면 환경변수로 조정한다:

```powershell
$env:GPU_QUEUE_MAX_CONCURRENT = "1"
$env:GPU_QUEUE_MAX_DEPTH = "10"
```
```

- [ ] **Step 2: 커밋**

```bash
git add README.md
git commit -m "docs: 가짜정보탐지 데스크탑 설정(Ollama, 위키 인덱스, GPU 큐) 안내 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## PART 2 — veritae-server (Spring)

### Task 12: `detection` 패키지에 가짜정보탐지 타입 추가

**Files:**
- Create: `src/main/java/com/veritae/veritae_server/detection/WikiEvidence.java`
- Create: `src/main/java/com/veritae/veritae_server/detection/MisinformationClaim.java`
- Create: `src/main/java/com/veritae/veritae_server/detection/MisinformationDetectionResult.java`
- Create: `src/main/java/com/veritae/veritae_server/detection/MisinformationDetectionJson.java`
- Test: `src/test/java/com/veritae/veritae_server/detection/MisinformationDetectionTypesTest.java`

**Interfaces:**
- Produces: `WikiEvidence(String title, String text, String url)`, `MisinformationClaim(String sentence, String reason, List<WikiEvidence> evidence)`, `MisinformationDetectionResult(String model, String wikiSnapshot, List<MisinformationClaim> claims)`, `MisinformationDetectionJson.toMisinformationDetection(Dto)`

- [ ] **Step 1: 실패 테스트 작성 (ScamDetectionTypesTest.java와 같은 패턴)**

```java
// src/test/java/com/veritae/veritae_server/detection/MisinformationDetectionTypesTest.java
package com.veritae.veritae_server.detection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MisinformationDetectionTypesTest {

    @Test
    void misinformationDetectionResult_exposesFieldsViaRecordAccessors() {
        var evidence = new WikiEvidence("선풍기 사망설", "선풍기 사망설은 미신이다.", "https://ko.wikipedia.org/wiki/선풍기_사망설");
        var claim = new MisinformationClaim("선풍기를 틀고 자면 사망한다.", "근거 문단이 주장을 부정합니다.", List.of(evidence));
        var result = new MisinformationDetectionResult("qwen3.5:4b", "2026-09-01", List.of(claim));

        assertThat(result.model()).isEqualTo("qwen3.5:4b");
        assertThat(result.wikiSnapshot()).isEqualTo("2026-09-01");
        assertThat(result.claims()).containsExactly(claim);
        assertThat(result.claims().get(0).evidence()).containsExactly(evidence);
    }

    @Test
    void misinformationDetectionJson_toMisinformationDetection_returnsNullForNullDto() {
        assertThat(MisinformationDetectionJson.toMisinformationDetection(null)).isNull();
    }

    @Test
    void misinformationDetectionJson_toMisinformationDetection_mapsNestedEvidence() {
        var evidenceDto = new MisinformationDetectionJson.WikiEvidenceDto(
                "선풍기 사망설", "선풍기 사망설은 미신이다.", "https://ko.wikipedia.org/wiki/선풍기_사망설");
        var claimDto = new MisinformationDetectionJson.ClaimDto(
                "선풍기를 틀고 자면 사망한다.", "근거 문단이 주장을 부정합니다.", List.of(evidenceDto));
        var dto = new MisinformationDetectionJson.Dto("qwen3.5:4b", "2026-09-01", List.of(claimDto));

        var result = MisinformationDetectionJson.toMisinformationDetection(dto);

        assertThat(result.model()).isEqualTo("qwen3.5:4b");
        assertThat(result.wikiSnapshot()).isEqualTo("2026-09-01");
        assertThat(result.claims()).hasSize(1);
        assertThat(result.claims().get(0).sentence()).isEqualTo("선풍기를 틀고 자면 사망한다.");
        assertThat(result.claims().get(0).evidence().get(0).title()).isEqualTo("선풍기 사망설");
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests MisinformationDetectionTypesTest`
Expected: FAIL(컴파일 에러 - 타입 없음)

- [ ] **Step 3: 레코드 3개 작성**

```java
// src/main/java/com/veritae/veritae_server/detection/WikiEvidence.java
package com.veritae.veritae_server.detection;

/** 가짜정보탐지 판정의 근거로 쓴 위키 조각 한 개. 위키백과 CC BY-SA 출처 표시 요건 때문에
 * title/url이 필수다. */
public record WikiEvidence(String title, String text, String url) {
}
```

```java
// src/main/java/com/veritae/veritae_server/detection/MisinformationClaim.java
package com.veritae.veritae_server.detection;

import java.util.List;

/** 거짓(반박)으로 판정된 문장 하나. 참/판단불가로 나온 문장은 애초에 이 타입으로
 * 만들어지지 않는다 - misinfo_infer.py가 반박만 결과에 담아 보낸다. */
public record MisinformationClaim(String sentence, String reason, List<WikiEvidence> evidence) {
}
```

```java
// src/main/java/com/veritae/veritae_server/detection/MisinformationDetectionResult.java
package com.veritae.veritae_server.detection;

import java.util.List;

/** 가짜정보(허위정보) 판별 결과. 텍스트가 전혀 추출되지 않으면 이 필드 자체가 없다(null),
 * scamDetection과 같은 조건. claims가 빈 배열이면 "검사했지만 반박되는 내용을 못 찾음"이지
 * "전부 사실"이 아니다. */
public record MisinformationDetectionResult(String model, String wikiSnapshot, List<MisinformationClaim> claims) {
}
```

- [ ] **Step 4: 공용 파서 작성 (ScamDetectionJson.java와 동일 패턴)**

```java
// src/main/java/com/veritae/veritae_server/detection/MisinformationDetectionJson.java
package com.veritae.veritae_server.detection;

import java.util.List;

/**
 * 탐지 서버 응답의 {@code misinformation_detection} JSON 필드를
 * {@link MisinformationDetectionResult}로 변환하는 공용 파싱 로직. ScamDetectionJson과
 * 동일한 패턴 - 세 HTTP 클라이언트가 각자 응답 스키마 안에서
 * {@code @JsonProperty("misinformation_detection") MisinformationDetectionJson.Dto} 필드로
 * 이 DTO를 참조한다.
 */
public final class MisinformationDetectionJson {

    private MisinformationDetectionJson() {
    }

    public record Dto(String model, String wikiSnapshot, List<ClaimDto> claims) {
    }

    public record ClaimDto(String sentence, String reason, List<WikiEvidenceDto> evidence) {
    }

    public record WikiEvidenceDto(String title, String text, String url) {
    }

    public static MisinformationDetectionResult toMisinformationDetection(Dto dto) {
        if (dto == null) {
            return null;
        }
        List<MisinformationClaim> claims = dto.claims().stream()
                .map(c -> new MisinformationClaim(
                        c.sentence(), c.reason(),
                        c.evidence().stream()
                                .map(e -> new WikiEvidence(e.title(), e.text(), e.url()))
                                .toList()))
                .toList();
        return new MisinformationDetectionResult(dto.model(), dto.wikiSnapshot(), claims);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests MisinformationDetectionTypesTest`
Expected: PASS (3개)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/veritae/veritae_server/detection/WikiEvidence.java src/main/java/com/veritae/veritae_server/detection/MisinformationClaim.java src/main/java/com/veritae/veritae_server/detection/MisinformationDetectionResult.java src/main/java/com/veritae/veritae_server/detection/MisinformationDetectionJson.java src/test/java/com/veritae/veritae_server/detection/MisinformationDetectionTypesTest.java
git commit -m "feat(misinfo): 가짜정보탐지 도메인 타입과 공용 JSON 파서 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 13: 3개 결과 레코드 + 3개 HTTP 클라이언트에 필드 연결

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/detection/ImageAnalysisResult.java`
- Modify: `src/main/java/com/veritae/veritae_server/detection/AudioAnalysisResult.java`
- Modify: `src/main/java/com/veritae/veritae_server/detection/VideoAnalysisResult.java`
- Modify: `src/main/java/com/veritae/veritae_server/detection/spai/SpaiHttpDetectionClient.java`
- Modify: `src/main/java/com/veritae/veritae_server/detection/antideepfake/AntiDeepfakeHttpDetectionClient.java`
- Modify: `src/main/java/com/veritae/veritae_server/detection/dfdc/DfdcHttpDetectionClient.java`
- Modify: `src/test/java/com/veritae/veritae_server/detection/ScamDetectionTypesTest.java`(레코드 생성자 인자 1개씩 늘어남)
- Test: `src/test/java/com/veritae/veritae_server/detection/spai/SpaiHttpDetectionClientTest.java` 등 3개(추가 검증)

**Interfaces:**
- Produces: `ImageAnalysisResult(ImageDetectionResult, ScamDetectionResult, MisinformationDetectionResult)`(3-arg), `AudioAnalysisResult`도 동일 구조 3-arg, `VideoAnalysisResult(VideoDetectionResult, ScamDetectionResult, MisinformationDetectionResult, String errorCode)`(4-arg)

- [ ] **Step 1: 레코드 3개에 필드 추가 (기존 테스트가 컴파일 에러로 즉시 실패하는 걸 "실패 테스트"로 삼는다 - 레코드는 생성자가 자동이라 별도 실패 테스트를 새로 쓸 필요가 없다)**

```java
// ImageAnalysisResult.java
public record ImageAnalysisResult(
        ImageDetectionResult aiDetection, ScamDetectionResult scamDetection,
        MisinformationDetectionResult misinformationDetection) {
}
```

```java
// AudioAnalysisResult.java
public record AudioAnalysisResult(
        AudioDetectionResult aiDetection, ScamDetectionResult scamDetection,
        MisinformationDetectionResult misinformationDetection) {
}
```

```java
// VideoAnalysisResult.java
public record VideoAnalysisResult(
        VideoDetectionResult aiDetection, ScamDetectionResult scamDetection,
        MisinformationDetectionResult misinformationDetection, String errorCode) {
}
```

- [ ] **Step 2: 빌드해서 어디가 깨지는지 확인**

Run: `./gradlew compileTestJava`
Expected: FAIL — `ScamDetectionTypesTest.java`, `AnalysisApiMapperTest.java`, `AnalysisApiControllerTest.java`, `AnalysisHistoryServiceTest.java`, `EndToEndScenarioTest.java`, `VideoAnalysisAsyncWorkerTest.java` 등에서 "constructor ImageAnalysisResult in record ImageAnalysisResult cannot be applied to given types" 류 에러 목록이 나온다. 이 목록이 이번 태스크(13)와 다음 태스크(14, 16)에서 고쳐야 할 파일 전체다.

- [ ] **Step 3: `ScamDetectionTypesTest.java`의 생성자 호출에 `null` 세 번째 인자 추가**

```java
    @Test
    void imageAnalysisResult_allowsNullScamDetection() {
        var aiDetection = new ImageDetectionResult("spai", 0.1, null);

        var result = new ImageAnalysisResult(aiDetection, null, null);

        assertThat(result.aiDetection()).isEqualTo(aiDetection);
        assertThat(result.scamDetection()).isNull();
    }

    @Test
    void audioAnalysisResult_andVideoAnalysisResult_exposeAiAndScamDetection() {
        var scamDetection = new ScamDetectionResult("lilju", 0.3, List.of());

        var audioResult = new AudioAnalysisResult(new AudioDetectionResult("antideepfake", 0.1, List.of()), scamDetection, null);
        var videoResult = new VideoAnalysisResult(new VideoDetectionResult("dfdc", 0.2, List.of(), null), scamDetection, null, null);

        assertThat(audioResult.scamDetection()).isEqualTo(scamDetection);
        assertThat(videoResult.scamDetection()).isEqualTo(scamDetection);
    }
```

(다른 두 파일, `AnalysisApiMapperTest.java`/`AnalysisApiControllerTest.java`/`AnalysisHistoryServiceTest.java`/`EndToEndScenarioTest.java`/`VideoAnalysisAsyncWorkerTest.java`의 수정은 Task 14와 16에서 그 파일들을 직접 건드리는 김에 같이 처리한다 — 지금은 컴파일만 되게 `ScamDetectionTypesTest`만 고치고 넘어가면 나머지는 여전히 깨져 있다는 걸 인지하고 다음 태스크로 넘어간다.)

- [ ] **Step 4: 3개 HTTP 클라이언트에 가짜정보탐지 파싱 추가**

`SpaiHttpDetectionClient.java`의 `SpaiResponse` 레코드와 `detectImage` 메서드를 아래로 교체:

```java
            var aiDetection = new ImageDetectionResult(
                    response.aiDetection().model(),
                    response.aiDetection().score(),
                    response.aiDetection().evidenceImage());
            return new ImageAnalysisResult(
                    aiDetection,
                    ScamDetectionJson.toScamDetection(response.scamDetection()),
                    MisinformationDetectionJson.toMisinformationDetection(response.misinformationDetection()));
        } catch (RestClientException e) {
            throw new DetectionServiceException("탐지 서버 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }

    private record SpaiResponse(
            @JsonProperty("ai_detection") AiDetection aiDetection,
            @JsonProperty("scam_detection") ScamDetectionJson.Dto scamDetection,
            @JsonProperty("misinformation_detection") MisinformationDetectionJson.Dto misinformationDetection) {
    }
```

(import에 `com.veritae.veritae_server.detection.MisinformationDetectionJson` 추가.)

`AntiDeepfakeHttpDetectionClient.java`의 `AntiDeepfakeResponse`와 반환문도 동일하게:

```java
            var aiDetection = new AudioDetectionResult(response.aiDetection().model(), response.aiDetection().score(), evidence);
            return new AudioAnalysisResult(
                    aiDetection,
                    ScamDetectionJson.toScamDetection(response.scamDetection()),
                    MisinformationDetectionJson.toMisinformationDetection(response.misinformationDetection()));
```

```java
    private record AntiDeepfakeResponse(
            @JsonProperty("ai_detection") AiDetection aiDetection,
            @JsonProperty("scam_detection") ScamDetectionJson.Dto scamDetection,
            @JsonProperty("misinformation_detection") MisinformationDetectionJson.Dto misinformationDetection) {
    }
```

`DfdcHttpDetectionClient.java`도 동일 패턴:

```java
            return new VideoAnalysisResult(
                    aiDetection,
                    ScamDetectionJson.toScamDetection(response.scamDetection()),
                    MisinformationDetectionJson.toMisinformationDetection(response.misinformationDetection()),
                    response.errorCode());
```

```java
    private record DfdcResponse(
            @JsonProperty("ai_detection") AiDetection aiDetection,
            @JsonProperty("scam_detection") ScamDetectionJson.Dto scamDetection,
            @JsonProperty("misinformation_detection") MisinformationDetectionJson.Dto misinformationDetection,
            @JsonProperty("error_code") String errorCode) {
    }
```

- [ ] **Step 5: 각 클라이언트 테스트에 가짜정보탐지 파싱 검증 추가**

`SpaiHttpDetectionClientTest.java`(파일 구조를 먼저 열어 기존 mock 패턴 확인 후) 기존 "정상 응답" 테스트 근처에 추가:

```java
    @Test
    void detectImage_parsesMisinformationDetectionWhenPresent() {
        // 이 파일의 다른 테스트가 쓰는 MockRestServiceServer(또는 동급) 셋업 패턴을 그대로 따라,
        // 아래 JSON을 응답 바디로 주고 검증한다. 정확한 mock 셋업 코드는 이 파일 상단의 기존
        // 테스트(예: 정상 이미지 응답 테스트)를 복사해서 만든다.
        String responseBody = """
                {
                  "ai_detection": {"model": "spai", "score": 0.1, "evidence_image": null},
                  "scam_detection": null,
                  "misinformation_detection": {
                    "model": "qwen3.5:4b",
                    "wiki_snapshot": "2026-09-01",
                    "claims": [
                      {
                        "sentence": "선풍기를 틀고 자면 사망한다.",
                        "reason": "근거 문단이 주장을 부정합니다.",
                        "evidence": [
                          {"title": "선풍기 사망설", "text": "미신이다.", "url": "https://ko.wikipedia.org/wiki/선풍기_사망설"}
                        ]
                      }
                    ]
                  }
                }
                """;
        // ... 이 파일의 기존 mock 서버 셋업으로 위 바디를 반환하게 하고 호출한 뒤:
        // var result = spaiHttpDetectionClient.detectImage(...);
        // assertThat(result.misinformationDetection().model()).isEqualTo("qwen3.5:4b");
        // assertThat(result.misinformationDetection().claims()).hasSize(1);
    }
```

> 이 스텝은 실행자가 `SpaiHttpDetectionClientTest.java`/`AntiDeepfakeHttpDetectionClientTest.java`/`DfdcHttpDetectionClientTest.java`를 먼저 Read해서 그 파일이 실제로 쓰는 HTTP mock 방식(`MockRestServiceServer` 등)을 확인한 뒤, 그 방식 그대로 위 JSON 바디를 검증하는 테스트로 채워 넣는다 — 이 계획 문서 시점에는 그 세 파일의 정확한 mock 셋업 코드를 옮겨적지 않았으므로, 실행자가 파일을 열어 기존 패턴을 그대로 복제한다(각 파일당 1개씩, 총 3개 테스트 추가).

- [ ] **Step 6: 컴파일 확인(전체 테스트는 아직 실패 - Task 14/16 전까지는 정상)**

Run: `./gradlew compileJava`
Expected: PASS (main 소스는 이제 컴파일됨)

Run: `./gradlew compileTestJava`
Expected: 여전히 FAIL — Task 14/16에서 고칠 파일들 때문. 이 시점에는 `ScamDetectionTypesTest`와 3개 클라이언트 테스트만 통과 확인한다:

Run: `./gradlew test --tests ScamDetectionTypesTest --tests SpaiHttpDetectionClientTest --tests AntiDeepfakeHttpDetectionClientTest --tests DfdcHttpDetectionClientTest`

(gradle이 컴파일 전체 실패로 이 명령 자체가 안 돌 수 있다 — 그렇다면 Step 3에서 언급한 나머지 깨진 파일들을 Task 14/16과 순서 상관없이 지금 다 같이 고쳐야 컴파일이 통과한다. 실행자는 Task 14/16을 먼저 훑어보고, 세 태스크를 사실상 하나의 커밋 사이클로 묶어 진행해도 된다 — 이 레포는 record 생성자 변경이 여러 파일에 동시에 영향을 주는 구조라 완전히 독립적으로 쪼개지지 않는다.)

- [ ] **Step 7: 커밋 (Task 14 완료 후 한 번에 커밋해도 무방 - Step 6의 이유와 동일)**

```bash
git add src/main/java/com/veritae/veritae_server/detection/
git commit -m "feat(misinfo): 3개 분석 결과 타입과 HTTP 클라이언트에 가짜정보탐지 필드 연결

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 14: `AnalysisRecord` 컬럼 + 서비스/워커 + 리포트 집계

**Files:**
- Modify: `src/main/java/com/veritae/veritae_server/domain/analysisrecord/AnalysisRecord.java`
- Modify: `src/main/java/com/veritae/veritae_server/domain/analysisrecord/AnalysisRecordRepository.java`
- Modify: `src/main/java/com/veritae/veritae_server/analysis/ImageAnalysisService.java`
- Modify: `src/main/java/com/veritae/veritae_server/analysis/AudioAnalysisService.java`
- Modify: `src/main/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorker.java`
- Modify: `src/main/java/com/veritae/veritae_server/analysis/AnalysisHistoryService.java`
- Modify: `src/main/java/com/veritae/veritae_server/api/AnalysisApiMapper.java`
- Modify 관련 테스트: `AnalysisRecordTest.java`, `AnalysisRecordRepositoryTest.java`, `AnalysisHistoryServiceTest.java`, `AnalysisApiMapperTest.java`

**Interfaces:**
- Produces: `AnalysisRecord.completedSync(memberId, modality, resultJson, aiScore, scamScore, misinfoRefutedCount)` 6-arg 오버로드(기존 5-arg는 유지, 내부에서 `misinfoRefutedCount=null`로 위임 — **기존 8개 파일의 5-arg 호출은 한 곳도 안 바꿔도 됨**), `markCompleted`/`markCompletedWithPartialError`도 동일하게 6-arg 오버로드 추가. `AnalysisRecordRepository.countByMemberIdAndStatusAndMisinfoRefutedCountGreaterThanEqual(...)`. `AnalysisHistoryService.AnalysisReportView`에 `misinformationDetectedCount` 필드 추가(7-arg record).

- [ ] **Step 1: `AnalysisRecord`에 컬럼 + 오버로드 추가 — 먼저 새 동작을 검증하는 실패 테스트**

`AnalysisRecordTest.java`를 열어 기존 테스트 스타일을 확인한 뒤(이미 이 파일이 `completedSync`/`markCompleted`를 테스트하고 있음), 아래 테스트를 추가:

```java
    @Test
    void completedSync_withMisinfoRefutedCount_storesIt() {
        var record = AnalysisRecord.completedSync(
                UUID.randomUUID(), Modality.IMAGE, "{}", 0.1, 0.2, 3);

        assertThat(record.getMisinfoRefutedCount()).isEqualTo(3);
    }

    @Test
    void completedSync_withoutMisinfoRefutedCount_defaultsToNull() {
        var record = AnalysisRecord.completedSync(
                UUID.randomUUID(), Modality.IMAGE, "{}", 0.1, 0.2);

        assertThat(record.getMisinfoRefutedCount()).isNull();
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests AnalysisRecordTest`
Expected: FAIL (컴파일 에러 - `getMisinfoRefutedCount()` 없음, 6-arg `completedSync` 없음)

- [ ] **Step 3: `AnalysisRecord.java` 구현**

`scamScore` 필드 선언 다음에 추가:

```java
    // 가짜정보탐지에서 반박(거짓)으로 판정된 문장 개수 - 리포트 집계용(result_json 파싱 없이
    // 바로 집계 쿼리 가능하게, ai_score/scam_score와 동일한 목적). misinformationDetection
    // 자체가 없으면(텍스트 없음) null - "검사 안 함"과 "0건 검사했지만 다 사실"을 구분한다.
    @Column(name = "misinfo_refuted_count")
    private Integer misinfoRefutedCount;
```

`completedSync`를 아래로 교체(기존 5-arg는 새 6-arg에 위임):

```java
    /** 이미지/음성 전용 — 동기 분석이 이미 끝난 결과를 COMPLETED 상태로 바로 생성(상태 전환 없음). */
    public static AnalysisRecord completedSync(
            UUID memberId, Modality modality, String resultJson, Double aiScore, Double scamScore) {
        return completedSync(memberId, modality, resultJson, aiScore, scamScore, null);
    }

    public static AnalysisRecord completedSync(
            UUID memberId, Modality modality, String resultJson, Double aiScore, Double scamScore,
            Integer misinfoRefutedCount) {
        Instant now = now();
        AnalysisRecord record = new AnalysisRecord(UUID.randomUUID(), memberId, modality, AnalysisJobStatus.COMPLETED, now);
        record.resultJson = resultJson;
        record.aiScore = aiScore;
        record.scamScore = scamScore;
        record.misinfoRefutedCount = misinfoRefutedCount;
        return record;
    }
```

`markCompleted`를 아래로 교체:

```java
    public void markCompleted(String resultJson, Double aiScore, Double scamScore) {
        markCompleted(resultJson, aiScore, scamScore, null);
    }

    public void markCompleted(String resultJson, Double aiScore, Double scamScore, Integer misinfoRefutedCount) {
        this.status = AnalysisJobStatus.COMPLETED;
        this.resultJson = resultJson;
        this.aiScore = aiScore;
        this.scamScore = scamScore;
        this.misinfoRefutedCount = misinfoRefutedCount;
        this.updatedAt = now();
    }
```

`markCompletedWithPartialError`를 아래로 교체:

```java
    public void markCompletedWithPartialError(
            String resultJson, Double aiScore, Double scamScore, String errorCode, String errorMessage) {
        markCompletedWithPartialError(resultJson, aiScore, scamScore, null, errorCode, errorMessage);
    }

    public void markCompletedWithPartialError(
            String resultJson, Double aiScore, Double scamScore, Integer misinfoRefutedCount,
            String errorCode, String errorMessage) {
        this.status = AnalysisJobStatus.COMPLETED;
        this.resultJson = resultJson;
        this.aiScore = aiScore;
        this.scamScore = scamScore;
        this.misinfoRefutedCount = misinfoRefutedCount;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.updatedAt = now();
    }
```

(`@Getter` 클래스 레벨 애노테이션이 이미 있으므로 `getMisinfoRefutedCount()`는 Lombok이 자동 생성한다 - 별도 작성 불필요.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests AnalysisRecordTest`
Expected: PASS

- [ ] **Step 5: 리포지토리에 집계 메서드 추가**

`AnalysisRecordRepository.java`의 `countByMemberIdAndStatusAndScamScoreGreaterThanEqual` 다음 줄에 추가:

```java
    long countByMemberIdAndStatusAndMisinfoRefutedCountGreaterThanEqual(UUID memberId, AnalysisJobStatus status, int misinfoRefutedCount);
```

- [ ] **Step 6: `ImageAnalysisService`/`AudioAnalysisService`/`VideoAnalysisAsyncWorker`에서 계산해서 전달**

`ImageAnalysisService.java`의 `analyzeImage` 메서드에서 `Double scamScore = ...;` 다음 줄에 추가:

```java
        Integer misinfoRefutedCount = result.misinformationDetection() != null
                ? result.misinformationDetection().claims().size() : null;
```

`AnalysisRecord.completedSync(...)` 호출을 6-arg로 교체:

```java
        AnalysisRecord record = AnalysisRecord.completedSync(
                memberId, Modality.IMAGE, writeResultJson(result), aiScore, scamScore, misinfoRefutedCount);
```

`AudioAnalysisService.java`도 동일 패턴(`Modality.AUDIO`로).

`VideoAnalysisAsyncWorker.java`의 `complete(...)` 메서드를 아래로 교체:

```java
    private void complete(AnalysisRecord record, VideoAnalysisResult result) {
        Double aiScore = result.aiDetection() != null ? result.aiDetection().score() : null;
        Double scamScore = result.scamDetection() != null ? result.scamDetection().score() : null;
        Integer misinfoRefutedCount = result.misinformationDetection() != null
                ? result.misinformationDetection().claims().size() : null;
        if (result.errorCode() != null) {
            log.info("영상 분석: 일부 판독 불가 jobId={} errorCode={}", record.getId(), result.errorCode());
            record.markCompletedWithPartialError(
                    writeResultJson(result), aiScore, scamScore, misinfoRefutedCount,
                    result.errorCode(), errorMessageFor(result.errorCode()));
        } else {
            record.markCompleted(writeResultJson(result), aiScore, scamScore, misinfoRefutedCount);
        }
    }
```

- [ ] **Step 7: `AnalysisHistoryService`에 리포트 집계 추가**

`AnalysisHistoryService.java`의 `getReport` 메서드에서 `long scamDetectedCount = ...;` 다음 줄에 추가:

```java
        long misinformationDetectedCount = analysisRecordRepository.countByMemberIdAndStatusAndMisinfoRefutedCountGreaterThanEqual(
                memberId, AnalysisJobStatus.COMPLETED, 1);
```

`return new AnalysisReportView(...)`를 교체:

```java
        return new AnalysisReportView(
                total, imageCount, audioCount, videoCount, aiDetectedCount, scamDetectedCount, misinformationDetectedCount);
```

`AnalysisReportView` record 선언을 교체:

```java
    public record AnalysisReportView(
            long totalCount, long imageCount, long audioCount, long videoCount,
            long aiDetectedCount, long scamDetectedCount, long misinformationDetectedCount) {
    }
```

- [ ] **Step 8: `AnalysisHistoryServiceTest.java` 수정**

기존 `getReport` 테스트가 `AnalysisReportView`를 6개 인자로 생성하거나 비교하는 부분을 찾아 7번째 인자(`misinformationDetectedCount`)를 추가하고, `countByMemberIdAndStatusAndMisinfoRefutedCountGreaterThanEqual`에 대한 mock/stub도 기존 다른 count 메서드들과 같은 방식으로 추가한다(이 파일이 Mockito를 쓰는지 실제 리포지토리를 쓰는지 먼저 Read로 확인 후, 그 방식 그대로 확장).

- [ ] **Step 9: `AnalysisApiMapper.toReportResponse`에 필드 연결**

```java
    public static com.veritae.veritae_server.openapi.model.AnalysisReportResponse toReportResponse(
            com.veritae.veritae_server.analysis.AnalysisHistoryService.AnalysisReportView view) {
        return new com.veritae.veritae_server.openapi.model.AnalysisReportResponse(
                view.totalCount(), view.imageCount(), view.audioCount(), view.videoCount(),
                view.aiDetectedCount(), view.scamDetectedCount(), view.misinformationDetectedCount());
    }
```

(이 시점에 `AnalysisReportResponse` 생성자는 아직 6-arg라 컴파일 에러 — Task 15에서 openapi.yaml을 고치고 코드젠을 다시 돌리면 해소된다. 지금은 이 매핑 코드만 미리 써두고 Task 15로 넘어간다.)

- [ ] **Step 10: `AnalysisApiMapperTest.java`의 `toReportResponse_shouldMapAllCounts` 갱신**

```java
    @Test
    void toReportResponse_shouldMapAllCounts() {
        var view = new AnalysisHistoryService.AnalysisReportView(23, 10, 8, 5, 3, 2, 1);

        var response = AnalysisApiMapper.toReportResponse(view);

        assertThat(response.getTotalCount()).isEqualTo(23);
        assertThat(response.getImageCount()).isEqualTo(10);
        assertThat(response.getAudioCount()).isEqualTo(8);
        assertThat(response.getVideoCount()).isEqualTo(5);
        assertThat(response.getAiDetectedCount()).isEqualTo(3);
        assertThat(response.getScamDetectedCount()).isEqualTo(2);
        assertThat(response.getMisinformationDetectedCount()).isEqualTo(1);
    }
```

이 파일의 다른 테스트들(`ImageAnalysisResult`/`AudioAnalysisResult`/`VideoAnalysisResult` 생성자를 직접 호출하는 곳)도 전부 세 번째 인자로 `null`(가짜정보탐지 없음)을 추가한다 — Task 13 Step 2에서 나온 컴파일 에러 목록 중 이 파일 몫이다.

- [ ] **Step 11: 컴파일/테스트 확인**

Run: `./gradlew compileTestJava`
Expected: 아직 `AnalysisApiControllerTest`, `EndToEndScenarioTest`, `VideoAnalysisAsyncWorkerTest`, `AnalysisRecordRepositoryTest`가 남아있으면 FAIL — Task 16에서 마저 고친다. 이 태스크가 다룬 파일만 우선 확인:

Run: `./gradlew test --tests AnalysisRecordTest --tests AnalysisHistoryServiceTest`
Expected: PASS(컴파일은 전체가 같이 되므로, Task 16까지 끝나야 이 명령도 정상 실행된다 — 순서상 Task 13/14/16을 한 사이클로 묶어 실행하는 걸 권장한다는 점을 Task 13 Step 6에서 이미 밝혔다).

- [ ] **Step 12: 커밋 (Task 16과 묶어서, 또는 Task 13/14/16을 한 번에)**

```bash
git add src/main/java/com/veritae/veritae_server/domain/analysisrecord/ src/main/java/com/veritae/veritae_server/analysis/ src/main/java/com/veritae/veritae_server/api/AnalysisApiMapper.java src/test/java/com/veritae/veritae_server/domain/analysisrecord/AnalysisRecordTest.java src/test/java/com/veritae/veritae_server/analysis/AnalysisHistoryServiceTest.java src/test/java/com/veritae/veritae_server/api/AnalysisApiMapperTest.java
git commit -m "feat(misinfo): 반박 개수 컬럼 추가, 리포트에 misinformationDetectedCount 집계

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 15: `openapi.yaml` 갱신 + 코드젠 재생성

**Files:**
- Modify: `src/main/resources/openapi.yaml`
- Modify: `scripts/generate_api_spec_pdf.py` (엔드포인트/필드 갱신 필요 시 — 먼저 Read해서 이 필드들을 하드코딩하는지 확인 후 필요한 부분만 갱신)

- [ ] **Step 1: 스키마 3개 추가**

`openapi.yaml`의 `ScamEvidence` 스키마(1141-1159줄 부근) 바로 뒤에 추가:

```yaml
    MisinformationDetectionResult:
      type: object
      required: [model, wikiSnapshot, claims]
      description: >-
        가짜정보(허위정보) 판별 결과. 텍스트가 전혀 추출되지 않으면 이 필드 자체가 없다(null).
        거짓(반박)으로 판정된 문장만 claims에 담긴다 - 참/판단불가는 노출하지 않는다. claims가
        빈 배열이면 "검사했지만 위키에서 반박되는 내용을 찾지 못했다"는 뜻이지 "모두 사실"이라는
        뜻이 아니다.
      properties:
        model:
          type: string
          nullable: false
          description: 가짜정보 판정에 사용된 로컬 LLM 이름.
          example: qwen3.5:4b
        wikiSnapshot:
          type: string
          nullable: false
          description: 대조에 쓴 한국어 위키백과 인덱스의 기준 날짜(YYYY-MM-DD). 이 날짜 이후에
            바뀐 위키 내용이나 최근 사건은 반영되지 않을 수 있다.
          example: '2026-09-01'
        claims:
          type: array
          nullable: false
          description: 거짓으로 판정된 문장 목록. 근거가 없으면 빈 배열.
          items:
            $ref: '#/components/schemas/MisinformationClaim'

    MisinformationClaim:
      type: object
      required: [sentence, reason, evidence]
      description: 위키백과 내용과 어긋나는(거짓으로 판정된) 문장 하나.
      properties:
        sentence:
          type: string
          nullable: false
          description: 거짓으로 판정된 문장 원문.
          example: 선풍기를 틀고 자면 사망한다.
        reason:
          type: string
          nullable: false
          description: 왜 거짓으로 판정했는지 한 문장 설명.
          example: 근거 문단은 선풍기 사망설을 과학적 근거가 없는 미신이라고 설명합니다.
        evidence:
          type: array
          nullable: false
          description: 판정에 쓴 위키백과 근거 조각 목록.
          items:
            $ref: '#/components/schemas/WikiEvidence'

    WikiEvidence:
      type: object
      required: [title, text, url]
      description: >-
        가짜정보 판정 근거로 쓴 위키백과 문서 조각. title/url은 위키백과 CC BY-SA 라이선스의
        출처 표시 요건 때문에 필수다 - 화면에도 "출처: 위키백과" 표기가 필요하다.
      properties:
        title:
          type: string
          nullable: false
          example: 선풍기 사망설
        text:
          type: string
          nullable: false
          description: 근거로 쓰인 위키백과 원문 조각(발췌).
          example: 선풍기 사망설이란, 밀폐된 방에서 얼굴을 향해 선풍기를 켜놓은 채로 잠을 자면 사망할 수 있다는 미신이다.
        url:
          type: string
          format: uri
          nullable: false
          example: https://ko.wikipedia.org/wiki/선풍기_사망설
```

- [ ] **Step 2: `ImageAnalysisResponse`/`AudioAnalysisResponse`에 필드 + 예시 추가**

`ImageAnalysisResponse`의 `scamDetection` 속성 블록(799-806줄) 바로 뒤에 추가:

```yaml
        misinformationDetection:
          allOf:
            - $ref: '#/components/schemas/MisinformationDetectionResult'
          nullable: true
          description: >-
            가짜정보(허위정보) 판별 결과. 텍스트가 전혀 추출되지 않으면 없음(null). 거짓으로
            판정된 문장만 담기고, claims가 빈 배열이면 검사는 했지만 반박 근거를 못 찾았다는
            뜻이다("모두 사실"이 아님). 가짜정보 파이프라인 자체가 실패한 경우는 502로 요청
            전체가 실패한다(scamDetection과 동일 원칙).
```

`AudioAnalysisResponse`에도 동일한 블록을 그 `scamDetection` 속성 뒤(823-830줄)에 추가.

두 응답의 Swagger 예시(`ai_and_scam` 등, 182-203줄/244-271줄)에는 `misinformationDetection`을 **넣지 않는다** — 기존 예시가 "사기 위험 텍스트가 있는 경우"를 보여주는 용도라, 가짜정보탐지까지 섞으면 예시가 무엇을 보여주려는 건지 흐려진다. 대신 각 엔드포인트에 새 예시 하나를 추가한다. `ImageAnalysisResponse` 200 응답의 `examples:` 블록(183-203줄)에 세 번째 예시로 추가:

```yaml
                misinformation:
                  summary: 텍스트에 위키백과와 어긋나는 주장이 있는 경우
                  value:
                    id: 11111111-1111-1111-1111-111111111111
                    aiDetection:
                      model: spai
                      score: 0.02
                    misinformationDetection:
                      model: qwen3.5:4b
                      wikiSnapshot: '2026-09-01'
                      claims:
                        - sentence: 선풍기를 틀고 자면 사망한다.
                          reason: 근거 문단은 선풍기 사망설을 과학적 근거가 없는 미신이라고 설명합니다.
                          evidence:
                            - title: 선풍기 사망설
                              text: 선풍기 사망설이란, 밀폐된 방에서 얼굴을 향해 선풍기를 켜놓은 채로 잠을 자면 사망할 수 있다는 미신이다.
                              url: https://ko.wikipedia.org/wiki/선풍기_사망설
```

같은 예시를 `AudioAnalysisResponse`의 `examples:`에도 (aiDetection을 audio용으로 바꿔서) 추가.

- [ ] **Step 3: `AnalysisJobResponse`와 `AnalysisRecordSummary`에도 필드 추가**

`AnalysisJobResponse`의 `scamDetection` 속성(799줄 부근, 앞서 확인한 위치) 뒤에 Step 2와 동일한 `misinformationDetection` 블록 추가. `AnalysisRecordSummary`의 `scamDetection` 속성(950줄 부근) 뒤에도 동일하게 추가.

- [ ] **Step 4: `AnalysisReportResponse`에 필드 추가**

`required` 목록을 교체:

```yaml
      required: [totalCount, imageCount, audioCount, videoCount, aiDetectedCount, scamDetectedCount, misinformationDetectedCount]
```

`scamDetectedCount` 속성 뒤에 추가:

```yaml
        misinformationDetectedCount:
          type: integer
          format: int64
          nullable: false
          description: 가짜정보(반박된 주장)가 하나 이상 발견된 건수.
          example: 1
```

- [ ] **Step 5: 코드젠 재생성 + 빌드 확인**

Run: `./gradlew compileJava`
Expected: PASS — `com.veritae.veritae_server.openapi.model.MisinformationDetectionResult` 등이 새로 생성되고, `AnalysisReportResponse` 생성자가 7-arg로 바뀌어 Task 14 Step 9의 코드가 컴파일된다.

- [ ] **Step 6: `scripts/generate_api_spec_pdf.py` 확인**

이 파일을 Read해서 엔드포인트/필드를 하드코딩하고 있으면(과거 기록상 "이미지/음성/영상job조회 케이스별 예시" 방식으로 되어 있을 가능성이 높음), `misinformationDetection` 관련 설명을 openapi.yaml에서 그대로 끌어오는 구조인지 확인한다. 하드코딩된 필드 목록이 있다면 `misinformationDetection` 한 줄을 추가한다 — 이 파일의 정확한 구조는 실행자가 Read 후 그 스타일에 맞춰 최소한으로 갱신한다.

Run: `python scripts/generate_api_spec_pdf.py` (레포 루트에서, 정확한 실행 방법은 파일 상단 docstring 확인)
Expected: `Desktop\Veritae-API 명세서.pdf`가 갱신됨 — 이 스텝은 데스크탑이 아니라도(PDF 출력 경로만 로컬이면) 이 개발 머신에서 실행 가능한지 스크립트를 먼저 확인. 안 되면 이 스텝은 건너뛰고 다음 세션에서 처리한다고 기록만 남긴다.

- [ ] **Step 7: 커밋**

```bash
git add src/main/resources/openapi.yaml scripts/generate_api_spec_pdf.py
git commit -m "docs(api): 가짜정보탐지 스키마·필드·예시를 API 명세에 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 16: 나머지 깨진 테스트 정리 + 전체 빌드 그린

**Files:**
- Modify: `src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java`
- Modify: `src/test/java/com/veritae/veritae_server/EndToEndScenarioTest.java`
- Modify: `src/test/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorkerTest.java`
- Modify: `src/test/java/com/veritae/veritae_server/domain/analysisrecord/AnalysisRecordRepositoryTest.java`
- (Task 13 Step 6에서 나온 컴파일 에러 전체 목록 기준으로, 위 4개 외에 남은 파일이 있으면 같은 방식으로 처리)

**Interfaces:**
- Consumes: Task 12~15에서 만든 모든 타입/오버로드

- [ ] **Step 1: 전체 컴파일해서 정확한 에러 목록 확보**

Run: `./gradlew compileTestJava`
Expected: 에러 목록 출력 — 각 에러가 가리키는 파일:줄 번호를 전부 적어둔다.

- [ ] **Step 2: 각 파일을 Read해서 두 종류 수정을 적용**

이 계획 시점에는 이 4개 파일의 정확한 내용을 옮겨적지 않았다(레포 상태에 따라 세부 호출부가 다를 수 있어, 지어내서 틀린 코드를 적어두는 것보다 실행자가 직접 확인하는 게 정확하다 - No Placeholders 원칙과 상충하지 않도록, **수정 방법 자체는 기계적이고 명확하다**):

1. `new ImageAnalysisResult(x, y)` / `new AudioAnalysisResult(x, y)` 형태의 2-arg 호출 → 세 번째 인자로 `null`(가짜정보탐지 없는 케이스) 추가. 테스트가 가짜정보탐지 값 자체를 검증하려는 게 아니라면 전부 `null`이면 충분하다.
2. `new VideoAnalysisResult(x, y, z)` 형태의 3-arg 호출(x=aiDetection, y=scamDetection, z=errorCode) → `new VideoAnalysisResult(x, y, null, z)`로 세 번째 자리에 `null` 삽입(errorCode는 그대로 마지막 자리).
3. `AnalysisRecord.completedSync(a, b, c, d, e)` 5-arg 호출은 **그대로 둔다** — Task 14에서 5-arg 오버로드를 남겨뒀으므로 컴파일 에러 대상이 아니다. 혹시 6-arg를 요구하는 에러가 나면 그건 5-arg 오버로드 위임이 잘못 구현된 것이니 Task 14 Step 3을 다시 확인한다.

각 파일에서 정확히 어떤 줄이 깨졌는지는 Step 1의 에러 목록을 그대로 따라간다.

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileTestJava`
Expected: PASS

- [ ] **Step 4: 전체 테스트 실행**

Run: `./gradlew test`
Expected: 전부 PASS

- [ ] **Step 5: 커밋**

```bash
git add src/test/java/com/veritae/veritae_server/api/AnalysisApiControllerTest.java src/test/java/com/veritae/veritae_server/EndToEndScenarioTest.java src/test/java/com/veritae/veritae_server/analysis/VideoAnalysisAsyncWorkerTest.java src/test/java/com/veritae/veritae_server/domain/analysisrecord/AnalysisRecordRepositoryTest.java
git commit -m "test(misinfo): 레코드 시그니처 변경으로 깨진 나머지 테스트 수정, 빌드 그린

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## 이 계획이 다루지 않는 것 (스펙 범위 밖, §1 그대로)

사기감지 모델(Lilju) 교체, AntiDeepfake의 GPU 전환, check-worthiness 필터, 사칭성 주장 검증. 데스크탑에서만 확인 가능한 실측(GPU 큐 대기시간, 인덱스 구축 시간, LLM 응답 속도, RAM 여유)은 Task 10의 회귀 스크립트와 README(Task 11)로 사용자가 직접 확인한다.
