#!/usr/bin/env bash
# 운영 DB(MySQL veritae)를 하루 한 번 백업한다. veritae-db-backup.timer가 매일 04:00(KST)에 root로 실행한다.
#
# 1. mysqldump(--single-transaction이라 서비스를 멈추지 않는다) → gzip → /var/backups/veritae/veritae-YYYYMMDD.sql.gz
# 2. 같은 파일을 Cloudflare R2(원본 파일 버킷의 backups/db/)에 올린다 - EC2가 사라지거나 AWS 계정이 끝나도 남는다
# 3. 서버 안은 14일, R2는 30일이 지난 백업을 지운다
#
# 복구 방법은 docs/operations.md를 본다.
set -euo pipefail

BACKUP_DIR=/var/backups/veritae
ENV_FILE=/etc/veritae/veritae.env
KEEP_LOCAL_DAYS=14
KEEP_REMOTE_DAYS=30

STAMP="$(date +%Y%m%d)"
FILE="$BACKUP_DIR/veritae-$STAMP.sql.gz"

install -d -m 700 "$BACKUP_DIR"

# root는 MySQL에 소켓 인증으로 접속한다(비밀번호 없음). 덤프에 CREATE DATABASE를 넣지 않아 복구 시 DB 이름을 고를 수 있다.
mysqldump --single-transaction --quick --routines --triggers --no-tablespaces veritae \
    | gzip -9 > "$FILE.tmp"
gzip -t "$FILE.tmp"
mv "$FILE.tmp" "$FILE"
chmod 600 "$FILE"
echo "백업 생성: $FILE ($(stat -c %s "$FILE") bytes)"

find "$BACKUP_DIR" -name 'veritae-*.sql.gz' -mtime +"$KEEP_LOCAL_DAYS" -print -delete

# R2 접속 정보는 Spring과 같은 env 파일에서 R2_ 줄만 읽는다(다른 비밀 값은 이 스크립트에 불러오지 않는다).
# 값 끝의 \r(CR)을 지운다 - 노트북에서 옮긴 값에 Windows 줄바꿈이 붙어 있으면 R2 주소가 깨진다(2026-10-29 실제 발생).
eval "$(grep -E '^R2_(ACCOUNT_ID|ACCESS_KEY_ID|SECRET_ACCESS_KEY|BUCKET)=' "$ENV_FILE" | tr -d '\r' | sed 's/^/export /')"
if [[ -z "${R2_ACCOUNT_ID:-}" || -z "${R2_ACCESS_KEY_ID:-}" || -z "${R2_SECRET_ACCESS_KEY:-}" || -z "${R2_BUCKET:-}" ]]; then
    echo "R2 설정이 없어 서버 안에만 백업했습니다." >&2
    exit 1
fi
R2_BASE="https://$R2_ACCOUNT_ID.r2.cloudflarestorage.com/$R2_BUCKET/backups/db"

# 키를 명령줄에 쓰면 다른 프로세스 목록에 보이므로 curl 설정을 표준 입력(-K -)으로 넘긴다.
r2() {
    printf 'user = "%s:%s"\n' "$R2_ACCESS_KEY_ID" "$R2_SECRET_ACCESS_KEY" \
        | curl -sS --fail --aws-sigv4 "aws:amz:auto:s3" -K - "$@"
}

r2 -T "$FILE" "$R2_BASE/veritae-$STAMP.sql.gz" -o /dev/null
echo "R2 업로드: backups/db/veritae-$STAMP.sql.gz"

# 이름에 날짜가 들어 있어 목록을 받지 않고 보관 기간이 막 지난 날의 백업만 지운다(없으면 R2가 그대로 성공을 돌려준다).
OLD_STAMP="$(date -d "-$KEEP_REMOTE_DAYS days" +%Y%m%d)"
r2 -X DELETE "$R2_BASE/veritae-$OLD_STAMP.sql.gz" -o /dev/null
echo "R2 정리: backups/db/veritae-$OLD_STAMP.sql.gz (있었다면 삭제)"
