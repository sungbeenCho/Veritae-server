# 운영 안내

운영 서버(AWS EC2, `veritae.duckdns.org`)를 업데이트하고, DB를 백업·복구하는 방법이다.
서버 구성은 [README](../README.md#운영-배포)를 본다.

## 서버 접속

노트북 Git Bash에서 Windows OpenSSH로 접속한다.

```bash
/c/Windows/System32/OpenSSH/ssh.exe -i ~/.ssh/veritae-key.pem ubuntu@veritae.duckdns.org
```

## 배포(코드 업데이트)

서버는 배포한 시점의 jar를 계속 실행한다. 코드를 고친 뒤 운영에 반영하려면 다시 배포한다.

```bash
scripts/deploy.sh               # 테스트 → 빌드 → 배포
scripts/deploy.sh --skip-tests  # 테스트를 이미 돌렸을 때
```

- 커밋하지 않은 변경이 있으면 배포하지 않는다. 지금 서버에서 도는 커밋은 서버의 `/opt/veritae/DEPLOYED_COMMIT`에 있다.
- 기존 jar는 `/opt/veritae/veritae-server.jar.prev`로 보관하고 교체한 뒤 재시작한다. 재시작 중 약 30초는 요청이 실패한다.
- 새 버전이 2분 안에 응답하지 않으면 스크립트가 이전 jar로 되돌린다.
- 접속 확인 질문에 막히지 않도록 질문이 필요하면 바로 실패한다. 새 PC에서 처음 배포한다면 위 "서버 접속"으로 한 번 직접 접속해 서버 키를 등록해 둔다.

새 버전이 뜨기는 했지만 기능에 문제가 있어 직접 되돌릴 때:

```bash
sudo install -o veritae -g veritae -m 664 /opt/veritae/veritae-server.jar.prev /opt/veritae/veritae-server.jar
sudo systemctl restart veritae
```

서버 로그는 `sudo journalctl -u veritae -n 200`으로 본다.

## DB 백업

`veritae-db-backup.timer`가 매일 04:00(KST)에 [`ops/db-backup/veritae-db-backup.sh`](../ops/db-backup/veritae-db-backup.sh)를 실행한다.
그 시각에 서버가 꺼져 있었으면 다음에 켜질 때 한 번 실행한다.

| 보관 위치 | 경로 | 보관 기간 |
|---|---|---|
| 서버 안 | `/var/backups/veritae/veritae-YYYYMMDD.sql.gz` | 14일 |
| Cloudflare R2 | 원본 파일 버킷의 `backups/db/veritae-YYYYMMDD.sql.gz` | 30일 |

- `mysqldump --single-transaction`이라 백업 중에도 서비스는 그대로 동작한다.
- R2에도 두는 이유: EC2가 사라지거나 AWS 계정이 끝나도(무료 플랜은 6개월 뒤 종료) 백업이 남는다.
- 백업에는 회원 이메일과 비밀번호 해시가 들어 있다. 서버 안 파일은 root만 읽을 수 있고, R2 버킷은 비공개다.

상태 확인과 수동 실행:

```bash
systemctl list-timers veritae-db-backup.timer          # 다음 실행 시각
sudo journalctl -u veritae-db-backup.service -n 20      # 최근 실행 결과
sudo systemctl start veritae-db-backup.service          # 지금 한 번 실행
```

스크립트를 고쳤다면 서버에 다시 설치한다(노트북에서 `scp`로 `/tmp`에 올린 뒤):

```bash
sudo install -o root -g root -m 750 /tmp/veritae-db-backup.sh /opt/veritae/bin/veritae-db-backup.sh
sudo install -o root -g root -m 644 /tmp/veritae-db-backup.service /tmp/veritae-db-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
```

## DB 복구

**복구하면 그 백업 이후의 가입·분석 기록은 사라진다.** 먼저 지금 상태를 백업해 두고(`sudo systemctl start veritae-db-backup.service`),
처음에는 아래 1~2로 임시 DB에 복원해 내용을 확인한 뒤 진행한다.

1. 백업 파일을 정한다. 서버 안에 있으면 `/var/backups/veritae/`의 파일을 쓰고, 서버를 새로 만든 경우에는 R2에서 받는다.

   ```bash
   sudo -i
   eval "$(grep -E '^R2_(ACCOUNT_ID|ACCESS_KEY_ID|SECRET_ACCESS_KEY|BUCKET)=' /etc/veritae/veritae.env | tr -d '\r' | sed 's/^/export /')"
   printf 'user = "%s:%s"\n' "$R2_ACCESS_KEY_ID" "$R2_SECRET_ACCESS_KEY" | curl -sS --fail --aws-sigv4 "aws:amz:auto:s3" -K - \
       "https://$R2_ACCOUNT_ID.r2.cloudflarestorage.com/$R2_BUCKET/backups/db/veritae-YYYYMMDD.sql.gz" -o /root/restore.sql.gz
   ```

2. 임시 DB에 복원해 확인한다.

   ```bash
   mysql -e "CREATE DATABASE veritae_restore_test;"
   gunzip -c /root/restore.sql.gz | mysql veritae_restore_test
   mysql veritae_restore_test -e "select count(*) from members; select count(*) from analysis_jobs;"
   mysql -e "DROP DATABASE veritae_restore_test;"
   ```

3. 운영 DB에 복구한다. 서버를 멈추고, 기존 DB를 비운 뒤 복원하고, 다시 켠다.

   ```bash
   systemctl stop veritae
   mysql -e "DROP DATABASE veritae; CREATE DATABASE veritae;"
   gunzip -c /root/restore.sql.gz | mysql veritae
   systemctl start veritae
   ```

   DB 계정 `veritae`의 권한은 DB 단위로 주어져 있어 DB를 다시 만들어도 그대로 유지된다. 시작하면 Flyway가 스키마 기록을 검증한다.

R2의 분석 원본 파일은 백업 대상이 아니다. 복구한 기록 중 원본이 이미 지워진 기록은 원본 다운로드만 404가 된다.

복구 절차는 2026-10-29에 R2에서 받은 백업을 임시 DB에 복원해 표 3개·회원·Flyway 기록이 모두 들어 있는 것을 확인했다.

## 탐지 서버(데스크탑) 켜고 끄기

탐지 서버는 분석이 필요할 때만 켠다. 꺼져 있으면 분석 요청은 503 `DETECTION_SERVER_OFFLINE`으로 끝나고, 로그인·기록 조회는 그대로 된다.
데스크탑 PowerShell에서 켜고, 그 창에서 `Ctrl+C`로 끈다.

```powershell
conda activate detection-api; cd C:\ai\veritae-detection-server; uvicorn app.main:app --host 0.0.0.0 --port 8000
```
