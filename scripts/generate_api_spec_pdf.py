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

    def _wrap_code_line(self, line, max_width):
        """code_block 한 줄이 상자 폭을 넘으면 문자 단위로 줄바꿈한다(2026-09-22 추가) -
        원래 code_block은 줄바꿈이 전혀 없어서, 값이 긴 JSON 문자열(예: errorMessage)이
        상자 밖으로 삐져나가 잘려 보이는 버그가 있었다. 한글/영문 혼합 폭 측정은
        _mixed_line과 동일한 폰트 전환 규칙을 따라야 정확하다."""
        wrapped, cur_line, cur_width = [], "", 0.0
        for seg in HANGUL_RE.findall(line):
            font = "Gulim" if re.match(r'^[\uac00-\ud7a3]+$', seg) else "Consolas"
            self.set_font(font, "", 9.0)
            for ch in seg:
                ch_w = self.get_string_width(ch)
                if cur_width + ch_w > max_width and cur_line:
                    wrapped.append(cur_line)
                    cur_line, cur_width = ch, ch_w
                else:
                    cur_line += ch
                    cur_width += ch_w
        wrapped.append(cur_line)
        return wrapped

    def _wrapped_code_lines(self, lines):
        max_w = self.content_w() - 22
        result = []
        for line in lines:
            result.extend(self._wrap_code_line(line, max_w))
        return result

    def code_block(self, lines):
        lines = self._wrapped_code_lines(lines)
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
            if resp_lines and isinstance(resp_lines[0], tuple):
                # 프론트가 마주칠 수 있는 여러 상태를 다 보여주기 위한 다중 예시:
                # [(라벨, JSON 줄 목록), ...] 형태 - 케이스 사이에 라벨을 넣어 구분한다.
                for i, (ex_label, ex_lines) in enumerate(resp_lines):
                    if i > 0:
                        self.ln(4)
                    # 라벨과 코드블록이 페이지 경계에서 따로 떨어지지 않게, 둘을 합친
                    # 높이로 미리 넘어갈지 판단한다(2026-09-22) - code_block() 혼자만
                    # 자기 높이를 체크하면 라벨만 이전 페이지 끝에 남는 문제가 있었음.
                    pair_h = LINE_BODY + self._code_block_height(ex_lines)
                    if self.get_y() + pair_h > self.page_break_trigger:
                        self.add_page()
                    self.set_font("Malgun", "B", 9.2)
                    self.set_text_color(*ACCENT)
                    self.cell(0, LINE_BODY, ex_label, ln=1)
                    self.set_text_color(*TEXT)
                    self.code_block(ex_lines)
            else:
                self.code_block(resp_lines)
        if resp_note:
            self.note(resp_note)
        if status_codes:
            # "상태 코드" 라벨과 목록이 쪽 경계에서 갈라지지 않게 둘을 합친 높이로 미리 넘긴다
            # (2026-09-26) - 섹션이 한 쪽보다 길 때 마지막 항목의 네모 기호만 앞 쪽에 남고
            # 글자는 다음 쪽으로 넘어가는 문제가 있었음(응답 예시 라벨+코드블록과 같은 원칙).
            if self.get_y() + LINE_BODY + 2 + self._bullets_height(status_codes) > self.page_break_trigger:
                self.add_page()
            self.label("상태 코드")
            self.ln(LINE_BODY + 2)
            self.bullets(status_codes)
        # 다음 섹션(번호)이 바로 이어붙어 보이지 않게, 엔드포인트 끝에 확실한 여백을
        # 추가로 준다(2026-09-22) - bullets()가 주는 기본 여백(3pt)만으로는 다음
        # section_header()의 구분선과 붙어 보이는 경우가 있었음.
        self.ln(14)

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
        return 10 + LINE_CODE * len(self._wrapped_code_lines(lines)) + 15

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
            if resp_lines and isinstance(resp_lines[0], tuple):
                for i, (_ex_label, ex_lines) in enumerate(resp_lines):
                    if i > 0:
                        h += 4
                    h += LINE_BODY
                    h += self._code_block_height(ex_lines)
            else:
                h += self._code_block_height(resp_lines)
        if resp_note:
            h += self._wrapped_lines("Malgun", "", 8.6, resp_note, self.content_w()) * 12.2 + 2
        if status_codes:
            h += LINE_BODY + (LINE_BODY + 2)
            h += self._bullets_height(status_codes)
        h += 14  # 다음 섹션과의 여백(_endpoint_body 끝의 self.ln(14)와 반드시 맞춰야 함)
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
        h = 28 + self._bullets_height(bullet_items) + 14  # +14: 다음 섹션과의 여백(endpoint()와 동일)
        self._keep_together(h)
        self.section_header(header_text)
        self.bullets(bullet_items)
        self.ln(14)

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
        지금 17행 규모에서는 문제되지 않는다.
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

# ---- 5. 회원 탈퇴 ----
pdf.endpoint(
    5, "회원 탈퇴", "POST", "/api/v1/members/me/withdrawal",
    "비밀번호를 한 번 더 확인한 뒤 회원을 탈퇴 처리한다. 회원 정보, 모든 분석 기록, 보관 중인 원본 파일이 "
    "전부 삭제되며 되돌릴 수 없다. 탈퇴 후에는 기존 access/refresh 토큰이 모두 401로 거부된다",
    req_body=[
        "{",
        '  "password": "veritae123"',
        "}",
    ],
    resp_lines=["(응답 바디 없음 - 204 No Content)"],
    resp_note=(
        "비밀번호가 틀리면 401이 아니라 400(INVALID_PASSWORD)이다 - 로그인 상태는 유효하고 입력값만 틀린 "
        "경우라, 토큰 만료(401)와 구분하기 위함이다."
    ),
    status_codes=[
        "204 No Content (탈퇴 완료)",
        "400 Bad Request (비밀번호 누락 VALIDATION_FAILED / 비밀번호 불일치 INVALID_PASSWORD)",
        "401 Unauthorized",
        "500 Internal Server Error",
    ],
)

# ---- 6. 이미지 분석 (AI 생성 여부 + 사기 위험도) ----
pdf.endpoint(
    6, "이미지 분석 (AI 생성 여부 + 사기 위험도)", "POST", "/api/v1/analysis/image",
    "업로드한 이미지가 AI로 생성되었을 확률과 사기(보이스피싱 등) 위험도를 함께 반환. 탐지 서버 호출이 "
    "끝날 때까지 응답을 기다리는 동기 방식(수 초~수십 초 소요 가능)",
    req_body=["MULTIPART", "file: 분석할 이미지 파일(jpeg/png/webp)"],
    resp_lines=[
        ("AI 생성 이미지로 판별 + 사기 위험 텍스트도 있는 경우", [
            "{",
            '  "id": "11111111-1111-1111-1111-111111111111",',
            '  "aiDetection": {',
            '    "model": "spai",',
            '    "score": 0.97,',
            '    "evidenceImage": "iVBORw0KGgoAAAANSUhEUgAA... (base64 PNG, 생략)"',
            "  },",
            '  "scamDetection": {',
            '    "model": "lilju",',
            '    "score": 0.82,',
            '    "evidence": [',
            "      {",
            '        "sentence": "지금 바로 계좌번호와 비밀번호를 알려주셔야 합니다.",',
            '        "score": 0.95',
            "      }",
            "    ]",
            "  }",
            "}",
        ]),
        ("실제 사진(AI 아님) + 텍스트가 전혀 없는 경우", [
            "{",
            '  "id": "11111111-1111-1111-1111-111111111111",',
            '  "aiDetection": {',
            '    "model": "spai",',
            '    "score": 0.000134',
            "  }",
            "}",
        ]),
    ],
    resp_note=(
        "null인 필드는 응답 JSON에 키 자체가 나오지 않는다(2026-09-23 정책 - 예: 이 케이스의 scamDetection, "
        "evidenceImage). evidenceImage: 판독 근거 히트맵(base64 PNG). 이미지의 어느 부분이 의심스러운지 "
        "시각적으로 보여준다(best-effort - 실패하면 필드 자체가 빠짐). scamDetection: 이미지에서 텍스트가 "
        "추출되면 그 내용의 사기 위험도(model/score/evidence)를 채워 반환하고, 텍스트가 전혀 없으면 필드가 "
        "빠진다 - 이 필드가 없으면 항상 '텍스트가 없었다'는 뜻이며, 사기감지 파이프라인 자체가 실패한 경우는 "
        "조용히 넘어가지 않고 502로 요청 전체가 실패한다(2026-09-22 정책)."
    ),
    status_codes=[
        "200 OK",
        "400 Bad Request (빈 파일/지원하지 않는 형식)",
        "401 Unauthorized",
        "502 Bad Gateway (탐지 서버 호출 실패)",
        "500 Internal Server Error",
    ],
)

# ---- 7. 음성 분석 (AI 합성 여부 + 사기 위험도) ----
pdf.endpoint(
    7, "음성 분석 (AI 합성 여부 + 사기 위험도)", "POST", "/api/v1/analysis/audio",
    "업로드한 음성이 AI로 생성(합성)되었을 확률/판독 근거(시간 구간)와 사기(보이스피싱 등) 위험도를 "
    "함께 반환. 동기 방식. 재생 길이가 5분을 넘으면 분석하지 않고 400(AUDIO_TOO_LONG)으로 거부한다 - "
    "앞부분만 잘라서 분석하지 않는다",
    req_body=["MULTIPART", "file: 분석할 음성 파일(wav/mp3/m4a/aac, 최대 5분/25MB)"],
    resp_lines=[
        ("합성 음성으로 판별 + 사기 위험 텍스트가 있는 경우", [
            "{",
            '  "id": "22222222-2222-2222-2222-222222222222",',
            '  "aiDetection": {',
            '    "model": "antideepfake",',
            '    "score": 0.91,',
            '    "evidence": [',
            "      {",
            '        "title": "시간 구간 이상 패턴",',
            '        "description": "0.5초~1.2초 구간에서 합성 흔적이 감지됨",',
            '        "tags": ["temporal"],',
            '        "startSec": 0.5,',
            '        "endSec": 1.2',
            "      }",
            "    ]",
            "  },",
            '  "scamDetection": {',
            '    "model": "lilju",',
            '    "score": 0.82,',
            '    "evidence": [',
            "      {",
            '        "sentence": "지금 바로 계좌번호와 비밀번호를 알려주셔야 합니다.",',
            '        "score": 0.95',
            "      }",
            "    ]",
            "  }",
            "}",
        ]),
        ("실제 음성(AI 아님) + 텍스트가 전혀 없는 경우", [
            "{",
            '  "id": "22222222-2222-2222-2222-222222222222",',
            '  "aiDetection": {',
            '    "model": "antideepfake",',
            '    "score": 0.0018,',
            '    "evidence": []',
            "  }",
            "}",
        ]),
    ],
    resp_note=(
        "null인 필드는 응답 JSON에 키 자체가 나오지 않는다(2026-09-23 정책 - 예: 이 케이스의 scamDetection). "
        "scamDetection: 음성에서 텍스트(발화)가 추출되면 그 내용의 사기 위험도를 채워 반환하고, 발화가 "
        "전혀 없으면 필드가 빠진다 - 이 필드가 없으면 항상 '텍스트가 없었다'는 뜻이며, 사기감지 파이프라인 "
        "자체가 실패한 경우는 조용히 넘어가지 않고 502로 요청 전체가 실패한다(2026-09-22 정책)."
    ),
    status_codes=[
        "200 OK",
        "400 Bad Request (빈 파일/지원하지 않는 형식/25MB 초과 INVALID_AUDIO_FILE, 5분 초과 AUDIO_TOO_LONG)",
        "401 Unauthorized",
        "502 Bad Gateway (탐지 서버 호출 실패)",
        "500 Internal Server Error",
    ],
)

# ---- 8. 영상 분석 요청 (얼굴조작 딥페이크 여부 + 사기 위험도) ----
pdf.endpoint(
    8, "영상 분석 요청 (얼굴조작 딥페이크 여부 + 사기 위험도)", "POST", "/api/v1/analysis/video",
    "업로드한 영상의 얼굴조작(face-swap) 딥페이크 여부와 말소리·화면 글자의 사기(보이스피싱 등) 위험도 "
    "분석을 비동기 작업으로 접수. 처리에 수십 "
    "초~수 분 걸릴 수 있어 즉시 202로 jobId를 반환하고, 결과는 9번 API로 폴링해 확인. 완전 생성형"
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

# ---- 9. 영상 분석 상태/결과 조회 ----
pdf.endpoint(
    9, "영상 분석 상태/결과 조회", "GET", "/api/v1/analysis/jobs/{jobId}",
    "8번 API로 접수한 작업의 현재 상태를 조회한다. status만으로 각 필드의 존재 여부를 추측하지 말고 "
    "aiDetection/scamDetection은 항상 각각 null 체크할 것(경우별 응답은 아래 설명 참고). 진행 중"
    "(PENDING/PROCESSING)이면 Retry-After 헤더(초)가 붙는다 - 다음 조회까지 그만큼 기다리면 된다. "
    "본인이 접수한 작업이 아니거나 이미지/음성 기록의 id를 넣으면 404 반환",
    req_params=["jobId (path, UUID): 조회할 작업 ID"],
    resp_lines=[
        ("대기 중 - 앞에 2건 대기 (헤더 Retry-After: 5)", [
            "{",
            '  "jobId": "11111111-1111-1111-1111-111111111111",',
            '  "status": "PENDING",',
            '  "jobsAhead": 2',
            "}",
        ]),
        ("처리 중 (헤더 Retry-After: 5)", [
            "{",
            '  "jobId": "11111111-1111-1111-1111-111111111111",',
            '  "status": "PROCESSING"',
            "}",
        ]),
        ("완료 - 얼굴판독/사기감지 둘 다 성공", [
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
            '  "scamDetection": {',
            '    "model": "lilju",',
            '    "score": 0.82,',
            '    "evidence": [',
            "      {",
            '        "sentence": "지금 바로 계좌번호와 비밀번호를 알려주셔야 합니다.",',
            '        "score": 0.95',
            "      }",
            "    ]",
            "  }",
            "}",
        ]),
        ("완료 - 얼굴없음(AI판독 불가), 사기감지는 성공", [
            "{",
            '  "jobId": "11111111-1111-1111-1111-111111111111",',
            '  "status": "COMPLETED",',
            '  "scamDetection": {',
            '    "model": "lilju",',
            '    "score": 0.82,',
            '    "evidence": [',
            "      {",
            '        "sentence": "지금 바로 계좌번호와 비밀번호를 알려주셔야 합니다.",',
            '        "score": 0.95',
            "      }",
            "    ]",
            "  },",
            '  "errorCode": "NO_FACE_DETECTED",',
            '  "errorMessage": "영상에서 얼굴을 찾을 수 없어 AI판독은 제공되지 않습니다. 얼굴이 잘 보이는 영상이면 판독도 함께 받을 수 있습니다."',
            "}",
        ]),
        ("완료 - 얼굴도 없고 텍스트(말소리·화면 글자)도 없음 (판독 결과 둘 다 없음, 오류 아님)", [
            "{",
            '  "jobId": "11111111-1111-1111-1111-111111111111",',
            '  "status": "COMPLETED",',
            '  "errorCode": "NO_FACE_DETECTED",',
            '  "errorMessage": "영상에서 얼굴을 찾을 수 없어 AI판독은 제공되지 않습니다. 얼굴이 잘 보이는 영상이면 판독도 함께 받을 수 있습니다."',
            "}",
        ]),
        ("완료 - 얼굴은 있지만 텍스트 없음 (scamDetection만 없음)", [
            "{",
            '  "jobId": "11111111-1111-1111-1111-111111111111",',
            '  "status": "COMPLETED",',
            '  "aiDetection": {',
            '    "model": "dfdc",',
            '    "score": 0.12,',
            '    "evidence": []',
            "  }",
            "}",
        ]),
        ("실패 (AI판독·사기감지 중 하나라도 처리 중 오류)", [
            "{",
            '  "jobId": "11111111-1111-1111-1111-111111111111",',
            '  "status": "FAILED",',
            '  "errorCode": "ANALYSIS_FAILED",',
            '  "errorMessage": "영상 분석 중 오류가 발생했습니다."',
            "}",
        ]),
    ],
    resp_note=(
        "status: PENDING | PROCESSING | COMPLETED | FAILED. null인 필드는 응답 JSON에 키 자체가 나오지 "
        "않는다(2026-09-23 정책 - 위 '처리 중' 예시처럼 status만 오고 나머지 필드는 다 빠질 수 있음). "
        "경우별 응답: (1) AI 판독과 사기감지 중 하나라도 처리 중 오류가 나면 성공한 쪽 결과도 함께 버리고 "
        "FAILED + ANALYSIS_FAILED가 된다(결과 필드 둘 다 없음, 같은 영상으로 재시도 가능) - 한쪽 결과만 "
        "담긴 COMPLETED는 오지 않는다. 사기감지가 실패했는데 결과가 비어 있으면 '사기 아님'으로 오해될 수 "
        "있어서다. (2) 얼굴을 찾지 못하면 COMPLETED + NO_FACE_DETECTED이고 aiDetection이 없다 - "
        "scamDetection은 얼굴과 무관하게 있을 수 있다. (3) 얼굴도 텍스트도 없으면 COMPLETED + "
        "NO_FACE_DETECTED이고 결과 필드가 둘 다 없다 - 오류가 아니라 '판독할 대상이 없었다'는 정상 결과다. "
        "(4) 얼굴은 있지만 텍스트가 없으면 COMPLETED이고 errorCode 없이 scamDetection만 없다. errorCode 없이 "
        "결과 필드가 둘 다 없는 COMPLETED는 오지 않는다. "
        "scamDetection: 영상의 텍스트는 말소리(음성 인식)와 화면 글자(자막 등)를 합친 것이다 - 이 필드가 "
        "없으면 '말소리도 화면 글자도 없었다'는 뜻이다(말소리만 없고 화면 글자가 있으면 채워진다). "
        "jobsAhead: status가 PENDING일 때만 오며, 이 작업보다 먼저 접수돼 아직 처리를 시작하지 않은 영상 "
        "작업 수다('앞에 N건 대기 중' 표시용, 영상은 접수 순서대로 처리됨). 0이어도 PENDING일 수 있다. "
        "Retry-After: PENDING/PROCESSING일 때만 붙는 응답 헤더(초, 현재 5). "
        "보관: 완료(COMPLETED/FAILED)된 작업은 삭제하지 않으므로(회원 탈퇴 시 제외) 앱을 나중에 다시 열어도 "
        "결과를 조회할 수 있다. errorMessage는 사용자에게 그대로 보여줄 문구로, 표현이 다듬어질 수 있어 분기 "
        "판단에는 절대 쓰지 말고 errorCode만 쓸 것."
    ),
    status_codes=[
        "200 OK",
        "401 Unauthorized",
        "404 Not Found (작업 없음, 본인 소유 아님, 또는 영상이 아닌 기록의 id)",
        "500 Internal Server Error",
    ],
)

# ---- 10. 분석 기록 목록 조회 ----
pdf.endpoint(
    10, "분석 기록 목록 조회", "GET", "/api/v1/analysis/records",
    "로그인한 회원이 완료한 분석(이미지/음성/영상) 기록을 최신순으로 최대 10건 반환. 페이지네이션은 "
    "지원하지 않음 - 처리중/실패한 기록은 포함되지 않으며, 영상 진행 상태 확인은 9번 API를 사용",
    req_params=["없음 (Authorization 헤더로 인증)"],
    resp_lines=[
        ("이미지 기록 - 글자 없는 사진(imageDetection만)", [
            "{",
            '  "content": [',
            "    {",
            '      "id": "11111111-1111-1111-1111-111111111111",',
            '      "modality": "IMAGE",',
            '      "createdAt": "2026-09-23T09:00:00Z",',
            '      "mediaAvailable": true,',
            '      "imageDetection": {',
            '        "model": "spai",',
            '        "score": 0.87,',
            '        "evidenceImage": "iVBORw0KGgoAAAANSUhEUgAA... (base64 PNG, 생략)"',
            "      }",
            "    }",
            "  ]",
            "}",
        ]),
        ("음성 기록 - audioDetection + 사기감지", [
            "{",
            '  "content": [',
            "    {",
            '      "id": "22222222-2222-2222-2222-222222222222",',
            '      "modality": "AUDIO",',
            '      "createdAt": "2026-09-23T08:30:00Z",',
            '      "mediaAvailable": true,',
            '      "audioDetection": {',
            '        "model": "antideepfake",',
            '        "score": 0.73,',
            '        "evidence": [',
            "          {",
            '            "title": "시간 구간 이상 패턴",',
            '            "description": "1.0초~4.0초 구간에서 합성 흔적이 감지됨",',
            '            "tags": ["temporal"],',
            '            "startSec": 1.0,',
            '            "endSec": 4.0',
            "          }",
            "        ]",
            "      },",
            '      "scamDetection": {',
            '        "model": "lilju",',
            '        "score": 0.82,',
            '        "evidence": [',
            "          {",
            '            "sentence": "지금 바로 계좌번호와 비밀번호를 알려주셔야 합니다.",',
            '            "score": 0.95',
            "          }",
            "        ]",
            "      }",
            "    }",
            "  ]",
            "}",
        ]),
        ("영상 기록 - 얼굴 없음(videoDetection 없음, 사기감지는 있음)", [
            "{",
            '  "content": [',
            "    {",
            '      "id": "33333333-3333-3333-3333-333333333333",',
            '      "modality": "VIDEO",',
            '      "createdAt": "2026-09-23T08:00:00Z",',
            '      "mediaAvailable": false,',
            '      "scamDetection": {',
            '        "model": "lilju",',
            '        "score": 0.82,',
            '        "evidence": [',
            "          {",
            '            "sentence": "지금 바로 계좌번호와 비밀번호를 알려주셔야 합니다.",',
            '            "score": 0.95',
            "          }",
            "        ]",
            "      },",
            '      "errorCode": "NO_FACE_DETECTED",',
            '      "errorMessage": "영상에서 얼굴을 찾을 수 없어 AI판독은 제공되지 않습니다. 얼굴이 잘 보이는 영상이면 판독도 함께 받을 수 있습니다."',
            "    }",
            "  ]",
            "}",
        ]),
    ],
    resp_note=(
        "content 항목마다 modality에 맞는 detection 필드(imageDetection/audioDetection/videoDetection) "
        "하나만 채워지고, 나머지 두 필드는 null이라 응답 JSON에 키 자체가 안 나온다(2026-09-23 정책) - "
        "클라이언트는 modality를 보고 어느 필드를 읽을지 판단할 것. errorCode는 완료는 됐지만 일부 판독이 "
        "정상적으로 비어있을 때만 채워진다(현재 값 NO_FACE_DETECTED) - 목록에는 완료된 기록만 나오므로 "
        "완전 실패 사유는 여기 오지 않는다. errorMessage: errorCode와 같은 경우에 채워지는, 사용자에게 그대로 "
        "보여줄 문구로 9번 API의 errorMessage와 같은 값이다(분기 판단에는 errorCode를 쓸 것). "
        "mediaAvailable: 원본 파일을 11번 API로 받을 수 있는지(항상 옴). false면 원본이 없으므로(보관 기간 "
        "지남, 원본 보관 기능 이전 기록 등) 요청하지 않아도 된다. scamDetection: 영상은 말소리와 화면 글자가 "
        "둘 다 없을 때 빠진다."
    ),
    status_codes=[
        "200 OK",
        "401 Unauthorized",
        "500 Internal Server Error",
    ],
)

# ---- 11. 분석 원본 파일 다운로드 ----
pdf.endpoint(
    11, "분석 원본 파일 다운로드", "GET", "/api/v1/analysis/records/{id}/media",
    "분석 기록의 원본 파일(업로드한 이미지/음성/영상)을 업로드 때의 형식 그대로 내려준다. Range 요청을 "
    "지원한다 - 영상을 끝까지 받기 전에 재생·탐색할 수 있다",
    req_params=[
        "id (path, UUID): 분석 기록 id - 10번 API 목록의 id, 6·7번 응답의 id, 8번의 jobId와 같은 값",
        "Range (header, 선택): 일부만 받을 때. 예) bytes=0-1048575",
    ],
    resp_lines=[
        ("전체 (Range 없음)", [
            "HTTP/1.1 200 OK",
            "Content-Type: video/mp4",
            "Content-Length: 52428800",
            "Accept-Ranges: bytes",
            'Content-Disposition: attachment; filename="33333333-3333-3333-3333-333333333333.mp4"',
            "",
            "(원본 파일 바이트)",
        ]),
        ("일부 (Range: bytes=0-1048575)", [
            "HTTP/1.1 206 Partial Content",
            "Content-Type: video/mp4",
            "Content-Length: 1048576",
            "Content-Range: bytes 0-1048575/52428800",
            "Accept-Ranges: bytes",
            'Content-Disposition: attachment; filename="33333333-3333-3333-3333-333333333333.mp4"',
            "",
            "(요청한 범위의 바이트)",
        ]),
    ],
    resp_note=(
        "Content-Disposition: 파일로 내려받는 응답임을 알리는 헤더로, 파일 이름은 \"기록id.확장자\"(원본 파일 "
        "이름은 보관하지 않음). 앱의 다운로드·재생에는 영향이 없다. "
        "보관 정책: 회원당 최신 10건의 원본만 보관한다(10번 목록과 같은 기준). 11번째부터는 원본만 삭제되고 "
        "분석 결과는 남는다. 분석이 완료된 기록만 원본을 보관한다(처리 중/실패한 영상은 원본 없음). 이 기능이 "
        "생기기 전의 기록도 원본이 없다. 원본은 저장소에서 암호화된 상태로 보관되며, 회원 탈퇴 시 전부 "
        "삭제된다. 원본이 없으면 404 MEDIA_NOT_AVAILABLE이 오며, 10번 목록의 mediaAvailable이 false인 기록은 "
        "요청하지 않아도 된다. 기록이 없거나 본인 것이 아니면 404 ANALYSIS_RECORD_NOT_FOUND."
    ),
    status_codes=[
        "200 OK (파일 전체)",
        "206 Partial Content (Range 요청)",
        "401 Unauthorized",
        "404 Not Found (ANALYSIS_RECORD_NOT_FOUND 기록 없음/본인 소유 아님, MEDIA_NOT_AVAILABLE 원본 없음)",
        "416 Range Not Satisfiable (Range가 파일 크기를 벗어남)",
        "500 Internal Server Error",
    ],
)

# ---- 12. 분석 리포트(통계) 조회 ----
pdf.endpoint(
    12, "분석 리포트(통계) 조회", "GET", "/api/v1/analysis/report",
    "로그인한 회원의 완료된 분석 기록을 집계한 통계를 반환. aiDetectedCount/scamDetectedCount는 점수 "
    "0.5 이상을 \"탐지됨\"으로 판단한 건수(고정 임계값)",
    req_params=["없음 (Authorization 헤더로 인증)"],
    resp_lines=[
        "{",
        '  "totalCount": 23,',
        '  "imageCount": 10,',
        '  "audioCount": 8,',
        '  "videoCount": 5,',
        '  "aiDetectedCount": 3,',
        '  "scamDetectedCount": 2',
        "}",
    ],
    status_codes=[
        "200 OK",
        "401 Unauthorized",
        "500 Internal Server Error",
    ],
)

# ---- 오류 응답 형식 ----
pdf.error_format_block(
    "오류 응답 형식",
    "모든 오류는 RFC 9457 형식(application/problem+json)으로 통일됨. violations의 message는 서버 검증 "
    "기본 문구라 서버 언어 설정에 따라 달라질 수 있고, 한 필드에 규칙이 여러 개 어긋나면 같은 field가 "
    "여러 번 온다 - 분기 처리는 errorCode/field로 할 것:",
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
        '    {',
        '      "field": "password",',
        r'      "message": "\"^(?=.*[A-Za-z])(?=.*\d).{8,20}$\"와 일치해야 합니다"',
        '    },',
        '    {',
        '      "field": "password",',
        '      "message": "크기가 8에서 20 사이여야 합니다"',
        '    }',
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
        ("400", "AUDIO_TOO_LONG", "음성 재생 길이 5분 초과"),
        ("400", "INVALID_VIDEO_FILE", "영상 파일이 비어있거나 지원하지 않는 형식/100MB 초과"),
        ("400", "INVALID_PASSWORD", "회원 탈퇴 시 비밀번호 불일치"),
        ("401", "INVALID_CREDENTIALS", "이메일 또는 비밀번호 불일치"),
        ("401", "INVALID_REFRESH_TOKEN", "refreshToken 무효/만료"),
        ("401", "UNAUTHORIZED", "액세스 토큰 누락/무효/만료"),
        ("404", "ANALYSIS_JOB_NOT_FOUND", "분석 작업을 찾을 수 없거나 본인 소유가 아님"),
        ("404", "ANALYSIS_RECORD_NOT_FOUND", "분석 기록을 찾을 수 없거나 본인 소유가 아님"),
        ("404", "MEDIA_NOT_AVAILABLE", "기록은 있지만 원본 파일이 없음"),
        ("409", "EMAIL_ALREADY_EXISTS", "이미 가입된 이메일"),
        ("416", "RANGE_NOT_SATISFIABLE", "원본 다운로드의 Range가 파일 크기를 벗어남"),
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
