\
# -*- coding: utf-8 -*-
#
# Veritae API 명세서 PDF 생성 스크립트.
# 원본(Veritae-API 명세서.pdf)에서 폰트/색상/여백을 그대로 추출해 재현한다.
# 필요 패키지: pip install fpdf2 fonttools   (렌더링 원본에는 pymupdf도 필요)
# 필요 폰트(Windows 기본 내장): malgun.ttf, malgunbd.ttf, consola.ttf, gulim.ttc
#
# 사용법: python build_spec.py [출력경로.pdf]
#   출력경로를 생략하면 이 스크립트와 같은 폴더에 "Veritae-API 명세서.pdf"로 저장한다.
#
import os
import sys
import re
import tempfile
from fpdf import FPDF
from fpdf.enums import XPos, YPos

FONT_DIR = r"C:\Windows\Fonts"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUT_PATH = sys.argv[1] if len(sys.argv) > 1 else os.path.join(SCRIPT_DIR, "Veritae-API 명세서.pdf")


def extract_gulimche():
    """gulim.ttc(폰트 컬렉션)에서 GulimChe 서체만 단독 ttf로 뽑아 fpdf2에 등록 가능하게 만든다."""
    from fontTools.ttLib import TTCollection
    tc = TTCollection(os.path.join(FONT_DIR, "gulim.ttc"))
    out = os.path.join(tempfile.gettempdir(), "GulimChe_extracted.ttf")
    tc.fonts[1].save(out)  # index 1 = GulimChe (0=Gulim, 2=Dotum, 3=DotumChe)
    return out


GULIM_EXTRACTED = extract_gulimche()

TEXT = (26, 26, 26)
ACCENT = (181, 80, 46)
DIVIDER = (221, 221, 221)
CODEBG = (243, 237, 230)

HANGUL_RE = re.compile(r'[\uac00-\ud7a3]+|[^\uac00-\ud7a3]+')

LINE_BODY = 13.3
LINE_CODE = 14.75


class Spec(FPDF):
    def __init__(self):
        super().__init__(unit="pt", format=(595.28, 841.89))
        self.set_margins(57, 40, 57)
        self.set_auto_page_break(True, margin=50)
        self.add_page()
        self.add_font("Malgun", "", f"{FONT_DIR}\\malgun.ttf")
        self.add_font("Malgun", "B", f"{FONT_DIR}\\malgunbd.ttf")
        self.add_font("Consolas", "", f"{FONT_DIR}\\consola.ttf")
        self.add_font("Gulim", "", GULIM_EXTRACTED)
        self.set_text_color(*TEXT)

    def content_w(self):
        return self.w - self.l_margin - self.r_margin

    def hr(self, gap_before=3, gap_after=10):
        self.ln(gap_before)
        y = self.get_y()
        self.set_draw_color(*DIVIDER)
        self.set_line_width(0.7)
        self.line(self.l_margin, y, self.w - self.r_margin, y)
        self.set_y(y + gap_after)

    def doc_title(self, text):
        self.set_font("Malgun", "B", 19.5)
        self.cell(0, 24, text, ln=1)
        self.ln(6)

    def section_header(self, text):
        self.set_font("Malgun", "B", 12.8)
        self.cell(0, 17, text, ln=1)
        self.hr(gap_before=2, gap_after=11)

    def label(self, text):
        self.set_font("Malgun", "B", 9.8)
        self.cell(self.get_string_width(text) + 2, LINE_BODY, text, ln=0)

    def field(self, label_text, value_text):
        self.label(label_text)
        self.set_font("Malgun", "", 9.8)
        avail = self.content_w() - (self.get_x() - self.l_margin)
        self.multi_cell(avail, LINE_BODY, ": " + value_text,
                         new_x=XPos.LMARGIN, new_y=YPos.NEXT)

    def field_url(self, url):
        self.label("URL")
        self.set_font("Malgun", "", 9.8)
        self.cell(self.get_string_width(": ") + 1, LINE_BODY, ": ", ln=0)
        x, y = self.get_x(), self.get_y()
        self.set_font("Consolas", "", 9.0)
        w = self.get_string_width(url) + 9
        self.set_fill_color(*CODEBG)
        self.rect(x, y + 1.2, w, 12.6, "F")
        self.set_text_color(*ACCENT)
        self.set_xy(x + 4.5, y)
        self.cell(w - 9, LINE_BODY, url, ln=1)
        self.set_text_color(*TEXT)
        self.ln(2)

    def bullets(self, items):
        self.set_font("Malgun", "", 9.8)
        for it in items:
            x, y = self.l_margin, self.get_y()
            self.set_fill_color(*TEXT)
            self.rect(x + 1, y + 4.7, 3.6, 3.6, "F")
            self.set_xy(x + 9, y)
            self.multi_cell(self.content_w() - 9, LINE_BODY, it,
                             new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.ln(3)

    def _mixed_line(self, s, x, y):
        self.set_xy(x, y)
        for seg in HANGUL_RE.findall(s):
            if re.match(r'^[\uac00-\ud7a3]+$', seg):
                self.set_font("Gulim", "", 9.0)
            else:
                self.set_font("Consolas", "", 9.0)
            self.cell(self.get_string_width(seg), LINE_CODE, seg, ln=0)

    def code_block(self, lines):
        pad_top, pad_bottom = 10, 15
        h = pad_top + LINE_CODE * len(lines) + pad_bottom
        if self.get_y() + h > self.page_break_trigger:
            self.add_page()
        x0, y0 = self.l_margin, self.get_y()
        self.set_fill_color(*CODEBG)
        self.rect(x0, y0, self.content_w(), h, "F")
        cur_y = y0 + pad_top
        for line in lines:
            self._mixed_line(line, x0 + 11, cur_y)
            cur_y += LINE_CODE
        self.set_xy(x0, y0 + h + 9)

    def note(self, text):
        self.set_font("Malgun", "", 8.6)
        self.set_text_color(90, 90, 90)
        self.multi_cell(self.content_w(), 12.2, text,
                         new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.set_text_color(*TEXT)
        self.ln(2)

    def para(self, text):
        self.set_font("Malgun", "", 9.8)
        self.multi_cell(self.content_w(), LINE_BODY, text,
                         new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.ln(2)

    def _endpoint_body(self, num, name, method, url, desc, req_body=None,
                        req_params=None, resp_lines=None, resp_note=None,
                        status_codes=None):
        self.section_header(f"{num}. {name}")
        self.field("Method", method)
        self.ln(1)
        self.field_url(url)
        self.field("설명", desc)
        self.ln(3)
        if req_params is not None:
            self.label("요청 파라미터")
            self.ln(LINE_BODY)
            self.bullets(req_params)
        if req_body is not None:
            self.label("요청 바디 (multipart/form-data)" if req_body[0] == "MULTIPART"
                       else "요청 바디")
            self.ln(LINE_BODY + 2)
            if req_body[0] == "MULTIPART":
                self.bullets(req_body[1:])
            else:
                self.code_block(req_body)
        if resp_lines is not None:
            self.label("응답 예시")
            self.ln(LINE_BODY + 2)
            self.code_block(resp_lines)
        if resp_note:
            self.note(resp_note)
        if status_codes:
            self.label("상태 코드")
            self.ln(LINE_BODY + 2)
            self.bullets(status_codes)

    def _wrapped_lines(self, font_fam, style, size, text, width):
        """multi_cell이 text를 width 안에 넣을 때 몇 줄이 될지 미리 계산한다(순수 계산,
        get_x/get_y를 쓰지 않으므로 렌더링 전 높이 예측에 안전하게 쓸 수 있다)."""
        self.set_font(font_fam, style, size)
        words = text.split(" ")
        lines, line = 1, ""
        for w in words:
            trial = (line + " " + w).strip()
            if self.get_string_width(trial) > width and line:
                lines += 1
                line = w
            else:
                line = trial
        return lines

    def _bullets_height(self, items, indent=9):
        avail = self.content_w() - indent
        total = 0
        for it in items:
            total += self._wrapped_lines("Malgun", "", 9.8, it, avail) * LINE_BODY
        return total + 3

    def _code_block_height(self, lines):
        return 10 + LINE_CODE * len(lines) + 15

    def _measure_endpoint(self, num, name, method, url, desc, req_body=None,
                           req_params=None, resp_lines=None, resp_note=None,
                           status_codes=None):
        h = 28  # section_header(header line + divider + gap)
        h += LINE_BODY + 1  # Method
        h += LINE_BODY + 2  # URL
        h += self._wrapped_lines("Malgun", "", 9.8, desc, self.content_w() - 76) * LINE_BODY
        h += 3
        if req_params is not None:
            h += LINE_BODY + LINE_BODY
            h += self._bullets_height(req_params)
        if req_body is not None:
            h += LINE_BODY + (LINE_BODY + 2)
            if req_body[0] == "MULTIPART":
                h += self._bullets_height(req_body[1:])
            else:
                h += self._code_block_height(req_body)
        if resp_lines is not None:
            h += LINE_BODY + (LINE_BODY + 2)
            h += self._code_block_height(resp_lines)
        if resp_note:
            h += self._wrapped_lines("Malgun", "", 8.6, resp_note, self.content_w()) * 12.2 + 2
        if status_codes:
            h += LINE_BODY + (LINE_BODY + 2)
            h += self._bullets_height(status_codes)
        return h

    def _keep_together(self, height):
        if self.get_y() + height > self.page_break_trigger:
            self.add_page()

    def endpoint(self, *args, **kwargs):
        """한 API 섹션(헤더~상태 코드)을 페이지 중간에서 잘리지 않게 렌더링한다.

        unbreakable()은 내부에서 get_y()를 쓰는 것을 금지하는데, 아래 렌더링 함수들
        (bullets/code_block/field_url 등)은 전부 현재 좌표를 직접 읽어 배경 사각형·불릿을
        그리므로 그 기능을 쓸 수 없다. 대신 렌더링 전에 필요한 높이를 미리 계산해서,
        남은 공간에 안 들어가면 미리 새 페이지로 넘긴다.
        """
        h = self._measure_endpoint(*args, **kwargs)
        self._keep_together(h)
        self._endpoint_body(*args, **kwargs)

    def intro_block(self, header_text, bullet_items):
        """"인증 방식" 같은, 헤더+불릿만으로 된 짧은 섹션을 한 페이지에 묶어 렌더링한다."""
        h = 28 + self._bullets_height(bullet_items)
        self._keep_together(h)
        self.section_header(header_text)
        self.bullets(bullet_items)

    def error_format_block(self, header_text, intro_text, code_lines):
        """"오류 응답 형식" 섹션(헤더+설명+예시 코드블록)을 한 페이지에 묶어 렌더링한다."""
        h = 28
        h += self._wrapped_lines("Malgun", "", 9.8, intro_text, self.content_w()) * LINE_BODY + 2
        h += self._code_block_height(code_lines)
        self._keep_together(h)
        self.section_header(header_text)
        self.para(intro_text)
        self.code_block(code_lines)

    def error_table(self, header_text, rows, footnote):
        """"오류 코드 정리" 표를 헤더~각주까지 통째로 렌더링한다(표 중간이 안 잘리게).

        표 전체가 새 페이지 시작 지점부터도 안 들어갈 만큼 길어지면(행 20개+ 등) 이 방식은
        그래도 넘쳐버리므로, 실제로 표가 그 정도로 길어질 경우엔 행 단위 분할로 바꿔야 한다.
        지금 11행 규모에서는 문제되지 않는다.
        """
        col_w = [45, 190, self.content_w() - 45 - 190]
        row_h = 20
        row_heights = []
        for _, _, desc in rows:
            nlines = 1 + self.get_string_width(desc) // (col_w[2] - 6)
            row_heights.append(max(row_h, 12 * (int(nlines) + 1)))
        h = 28 + 16 + sum(row_heights) + 6
        h += self._wrapped_lines("Malgun", "", 8.6, footnote, self.content_w()) * 12.2 + 2
        self._keep_together(h)

        self.section_header(header_text)
        self.set_font("Malgun", "B", 9.2)
        self.set_fill_color(*CODEBG)
        self.set_draw_color(*DIVIDER)
        self.cell(col_w[0], 16, "상태 코드", border=1, fill=True, align="C")
        self.cell(col_w[1], 16, "errorCode", border=1, fill=True, align="C")
        self.cell(col_w[2], 16, "설명", border=1, fill=True, align="C",
                  new_x=XPos.LMARGIN, new_y=YPos.NEXT)

        self.set_font("Malgun", "", 8.8)
        for (status, code, desc), h_row in zip(rows, row_heights):
            x, y = self.get_x(), self.get_y()
            self.multi_cell(col_w[0], h_row, status, border=1, align="C")
            self.set_xy(x + col_w[0], y)
            self.set_font("Consolas", "", 8.2)
            self.multi_cell(col_w[1], h_row, code, border=1, align="L")
            self.set_font("Malgun", "", 8.8)
            self.set_xy(x + col_w[0] + col_w[1], y)
            self.multi_cell(col_w[2], h_row, desc, border=1, align="L")
            self.set_xy(x, y + h_row)

        self.ln(6)
        self.note(footnote)


pdf = Spec()

# ---- Title & auth ----
pdf.doc_title("API 명세서")
pdf.intro_block("인증 방식", [
    "JWT (Bearer 토큰)",
    "보호된 API는 요청 헤더에 Authorization: Bearer <accessToken> 필요",
    "accessToken 만료 시간: 1800초(30분)",
])

# ---- 1. 회원가입 ----
pdf.endpoint(
    1, "회원가입", "POST", "/api/v1/auth/signup",
    "이메일/비밀번호/닉네임으로 신규 회원 생성. 비밀번호는 응답에 포함되지 않음",
    req_body=[
        "{",
        '  "email": "user@veritae.app",',
        '  "password": "veritae123",',
        '  "nickname": "진실이"',
        "}",
    ],
    resp_lines=[
        "{",
        '  "id": "3f2504e0-4f89-41d3-9a0c-0305e82c3301",',
        '  "email": "user@veritae.app",',
        '  "nickname": "진실이"',
        "}",
    ],
    status_codes=[
        "201 Created",
        "400 Bad Request (입력값 검증 실패)",
        "409 Conflict (이미 가입된 이메일)",
        "500 Internal Server Error",
    ],
)

# ---- 2. 로그인 ----
pdf.endpoint(
    2, "로그인", "POST", "/api/v1/auth/login",
    "자격 증명 검증 후 access/refresh 토큰(JWT) 발급",
    req_body=[
        "{",
        '  "email": "user@veritae.app",',
        '  "password": "veritae123"',
        "}",
    ],
    resp_lines=[
        "{",
        '  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",',
        '  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",',
        '  "tokenType": "Bearer",',
        '  "expiresIn": 1800',
        "}",
    ],
    status_codes=[
        "200 OK",
        "400 Bad Request (입력값 검증 실패)",
        "401 Unauthorized (이메일/비밀번호 불일치, 구분하지 않음)",
        "500 Internal Server Error",
    ],
)

# ---- 3. 액세스 토큰 갱신 ----
pdf.endpoint(
    3, "액세스 토큰 갱신", "POST", "/api/v1/auth/refresh",
    "유효한 refreshToken으로 새 accessToken 발급. refreshToken 자체는 회전하지 않음",
    req_body=[
        "{",
        '  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."',
        "}",
    ],
    resp_lines=[
        "{",
        '  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",',
        '  "tokenType": "Bearer",',
        '  "expiresIn": 1800',
        "}",
    ],
    status_codes=[
        "200 OK",
        "400 Bad Request (입력값 검증 실패)",
        "401 Unauthorized (refreshToken 무효/만료)",
        "500 Internal Server Error",
    ],
)

# ---- 4. 내 정보 조회 ----
pdf.endpoint(
    4, "내 정보 조회", "GET", "/api/v1/members/me",
    "Bearer 액세스 토큰으로 인증된 회원 본인의 정보 반환",
    req_params=["없음 (Authorization 헤더로 인증)"],
    resp_lines=[
        "{",
        '  "id": "3f2504e0-4f89-41d3-9a0c-0305e82c3301",',
        '  "email": "user@veritae.app",',
        '  "nickname": "진실이"',
        "}",
    ],
    status_codes=[
        "200 OK",
        "401 Unauthorized (토큰 누락/무효/만료)",
        "500 Internal Server Error",
    ],
)

# ---- 5. 이미지 AI 판독 ----
pdf.endpoint(
    5, "이미지 AI 판독", "POST", "/api/v1/analysis/image",
    "업로드한 이미지가 AI로 생성되었을 확률을 점수로 반환. 탐지 서버 호출이 끝날 때까지 응답을 "
    "기다리는 동기 방식(수 초~수십 초 소요 가능)",
    req_body=["MULTIPART", "file: 분석할 이미지 파일(jpeg/png/webp)"],
    resp_lines=[
        "{",
        '  "aiDetection": {',
        '    "model": "spai",',
        '    "score": 0.000134,',
        '    "evidenceImage": null',
        "  }",
        "}",
    ],
    resp_note="evidenceImage: 판독 근거 히트맵(base64 PNG). 히트맵 기능은 설계만 승인되고 구현 보류 중이라 현재는 항상 null.",
    status_codes=[
        "200 OK",
        "400 Bad Request (빈 파일/지원하지 않는 형식)",
        "401 Unauthorized",
        "502 Bad Gateway (탐지 서버 호출 실패)",
        "500 Internal Server Error",
    ],
)

# ---- 6. 음성 AI 판독 ----
pdf.endpoint(
    6, "음성 AI 판독", "POST", "/api/v1/analysis/audio",
    "업로드한 음성이 AI로 생성(합성)되었을 확률과 판독 근거(시간 구간) 반환. 동기 방식",
    req_body=["MULTIPART", "file: 분석할 음성 파일(wav/mp3/m4a/aac, 최대 5분/25MB)"],
    resp_lines=[
        "{",
        '  "aiDetection": {',
        '    "model": "antideepfake",',
        '    "score": 0.0018,',
        '    "evidence": [',
        "      {",
        '        "title": "시간 구간 이상 패턴",',
        '        "description": "0.5초~1.2초 구간에서 합성 흔적이 감지됨",',
        '        "tags": ["temporal"],',
        '        "startSec": 0.5,',
        '        "endSec": 1.2',
        "      }",
        "    ]",
        "  }",
        "}",
    ],
    status_codes=[
        "200 OK",
        "400 Bad Request (빈 파일/지원하지 않는 형식/25MB 초과)",
        "401 Unauthorized",
        "502 Bad Gateway (탐지 서버 호출 실패)",
        "500 Internal Server Error",
    ],
)

# ---- 7. 영상 AI 판독 요청 ----
pdf.endpoint(
    7, "영상 AI 판독 요청", "POST", "/api/v1/analysis/video",
    "업로드한 영상의 얼굴조작(face-swap) 딥페이크 여부 분석을 비동기 작업으로 접수. 처리에 수십 "
    "초~수 분 걸릴 수 있어 즉시 202로 jobId를 반환하고, 결과는 8번 API로 폴링해 확인. 완전 생성형"
    "(Sora류) 영상 탐지는 미지원 - 얼굴조작 딥페이크만 판독",
    req_body=["MULTIPART", "file: 분석할 영상 파일(mp4/mov/avi, 최대 100MB)"],
    resp_lines=[
        "{",
        '  "jobId": "11111111-1111-1111-1111-111111111111"',
        "}",
    ],
    status_codes=[
        "202 Accepted",
        "400 Bad Request (빈 파일/지원하지 않는 형식/100MB 초과)",
        "401 Unauthorized",
        "500 Internal Server Error",
    ],
)

# ---- 8. 분석 작업 상태/결과 조회 (M4 반영: errorCode 추가) ----
pdf.endpoint(
    8, "분석 작업 상태/결과 조회", "GET", "/api/v1/analysis/jobs/{jobId}",
    "7번 API로 접수한 작업의 현재 상태 조회. status가 COMPLETED일 때만 aiDetection이, FAILED일 "
    "때만 errorCode/errorMessage가 채워짐. 본인이 접수한 작업이 아니면 404 반환",
    req_params=["jobId (path, UUID): 조회할 작업 ID"],
    resp_lines=[
        "{",
        '  "jobId": "11111111-1111-1111-1111-111111111111",',
        '  "status": "COMPLETED",',
        '  "aiDetection": {',
        '    "model": "dfdc",',
        '    "score": 0.91,',
        '    "evidence": [',
        "      {",
        '        "title": "얼굴 조작 의심 구간",',
        '        "description": "3.0초~7.0초 구간에서 얼굴 합성 흔적이 감지됨",',
        '        "tags": ["temporal", "face-swap"],',
        '        "startSec": 3.0,',
        '        "endSec": 7.0',
        "      }",
        "    ],",
        '    "evidenceImage": "iVBORw0KGgoAAAANSUhEUgAA... (base64 PNG, 생략)"',
        "  },",
        '  "errorCode": null,',
        '  "errorMessage": null',
        "}",
    ],
    resp_note=(
        "status: PENDING | PROCESSING | COMPLETED | FAILED. FAILED일 때 errorCode/errorMessage가 "
        "함께 채워진다. errorCode는 NO_FACE_DETECTED(영상에서 얼굴을 찾지 못함 - 다른 영상으로 재시도 "
        "유도) 또는 ANALYSIS_FAILED(그 외 처리 실패 - 같은 영상으로 재시도 가능) 중 하나이며, 클라이언트의 "
        "분기 처리는 이 값을 써야 한다. errorMessage는 사용자에게 그대로 보여줄 문구로, 표현이 다듬어질 "
        "수 있어 분기 판단에는 쓰지 않는다."
    ),
    status_codes=[
        "200 OK",
        "401 Unauthorized",
        "404 Not Found (작업 없음 또는 본인 소유 아님)",
        "500 Internal Server Error",
    ],
)

# ---- 오류 응답 형식 ----
pdf.error_format_block(
    "오류 응답 형식",
    "모든 오류는 RFC 9457 형식(application/problem+json)으로 통일됨:",
    [
        "{",
        '  "type": "https://api.veritae.app/errors/validation",',
        '  "title": "Validation Failed",',
        '  "status": 400,',
        '  "detail": "요청 값 검증에 실패했습니다.",',
        '  "instance": "/api/v1/auth/signup",',
        '  "errorCode": "VALIDATION_FAILED",',
        '  "timestamp": "2026-07-17T09:00:00Z",',
        '  "violations": [',
        '    { "field": "password", "message": "8~20자, 영문자와 숫자를 각각 1자 이상 포함해야 합니다." }',
        "  ]",
        "}",
    ],
)

# ---- 오류 코드 정리 ----
pdf.error_table(
    "오류 코드 정리",
    [
        ("400", "VALIDATION_FAILED", "입력값 검증 실패"),
        ("400", "INVALID_IMAGE_FILE", "이미지 파일이 비어있거나 지원하지 않는 형식"),
        ("400", "INVALID_AUDIO_FILE", "음성 파일이 비어있거나 지원하지 않는 형식/25MB 초과"),
        ("400", "INVALID_VIDEO_FILE", "영상 파일이 비어있거나 지원하지 않는 형식/100MB 초과"),
        ("401", "INVALID_CREDENTIALS", "이메일 또는 비밀번호 불일치"),
        ("401", "INVALID_REFRESH_TOKEN", "refreshToken 무효/만료"),
        ("401", "UNAUTHORIZED", "액세스 토큰 누락/무효/만료"),
        ("404", "ANALYSIS_JOB_NOT_FOUND", "분석 작업을 찾을 수 없거나 본인 소유가 아님"),
        ("409", "EMAIL_ALREADY_EXISTS", "이미 가입된 이메일"),
        ("502", "DETECTION_SERVICE_UNAVAILABLE", "탐지 서버(3060Ti 데스크탑) 호출 실패"),
        ("500", "INTERNAL_SERVER_ERROR", "예기치 못한 서버 오류"),
    ],
    "※ 위 표에 없는 값도 올 수 있다: 명시적으로 처리하지 않은 프레임워크 레벨 오류(예: 지원하지 않는 "
    "media type → 415, 지원하지 않는 HTTP method → 405)에서는 해당 HttpStatus의 enum 이름(예: "
    "UNSUPPORTED_MEDIA_TYPE, METHOD_NOT_ALLOWED)이 errorCode로 대신 내려간다. 닫힌 목록이 아닌 "
    "열린 문자열 집합으로 다뤄야 한다.",
)

pdf.output(OUT_PATH)
print("written:", OUT_PATH)
