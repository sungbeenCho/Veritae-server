#!/usr/bin/env bash
# 운영 서버(AWS EC2)에 지금 커밋을 배포한다. 노트북 Git Bash에서 저장소 루트 기준으로 실행한다.
#
#   scripts/deploy.sh               # 테스트 → 빌드 → 배포
#   scripts/deploy.sh --skip-tests  # 테스트를 이미 돌렸을 때
#
# 순서: 커밋 안 한 변경이 있으면 거부 → 테스트·bootJar → jar를 EC2로 복사 → 기존 jar를 .prev로 보관하고 교체 →
# 서비스 재시작 → 서버가 응답할 때까지 최대 2분 확인 → 실패하면 이전 jar로 되돌린다. 배포한 커밋은
# 서버의 /opt/veritae/DEPLOYED_COMMIT에 남는다.
#
# 환경변수(선택): VERITAE_DEPLOY_HOST(기본 ubuntu@veritae.duckdns.org), VERITAE_SSH_KEY(기본 ~/.ssh/veritae-key.pem)
set -euo pipefail

HOST="${VERITAE_DEPLOY_HOST:-ubuntu@veritae.duckdns.org}"
KEY="${VERITAE_SSH_KEY:-$HOME/.ssh/veritae-key.pem}"
# Windows에서는 Git Bash의 ssh 대신 Windows OpenSSH를 쓴다 - 키 파일 권한 검사를 Windows 방식으로 통과한다.
if [[ -x /c/Windows/System32/OpenSSH/ssh.exe ]]; then
    SSH=/c/Windows/System32/OpenSSH/ssh.exe
    SCP=/c/Windows/System32/OpenSSH/scp.exe
else
    SSH=ssh
    SCP=scp
fi

# 접속 중 확인 질문(처음 보는 서버 키 등)이 생기면 대답을 기다리며 멈추지 말고 바로 실패한다(2026-10-29, 질문에
# 막혀 업로드가 시작도 못 한 채 멈춘 적이 있다). 서버 키는 처음 한 번 직접 접속해 known_hosts에 등록해 둔다.
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -i "$KEY")

SKIP_TESTS=false
if [[ "${1:-}" == "--skip-tests" ]]; then
    SKIP_TESTS=true
elif [[ $# -gt 0 ]]; then
    echo "알 수 없는 옵션: $1 (사용법: scripts/deploy.sh [--skip-tests])" >&2
    exit 2
fi

cd "$(dirname "$0")/.."

if [[ -n "$(git status --porcelain)" ]]; then
    echo "커밋하지 않은 변경이 있어 배포하지 않습니다. 배포된 버전이 어느 커밋인지 알 수 있도록 먼저 커밋하세요." >&2
    git status --short >&2
    exit 1
fi

COMMIT="$(git rev-parse --short HEAD)"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
echo "배포할 커밋: $COMMIT ($BRANCH) $(git log -1 --format=%s)"
if [[ "$BRANCH" != "main" ]]; then
    echo "주의: main이 아닌 브랜치를 배포합니다."
fi

if [[ "$SKIP_TESTS" == true ]]; then
    ./gradlew clean bootJar -q
else
    ./gradlew clean test bootJar -q
fi
JAR="$(ls build/libs/*.jar | grep -v -- '-plain.jar$' | head -1)"
echo "빌드 완료: $JAR"

"$SCP" -q "${SSH_OPTS[@]}" "$JAR" "$HOST:/tmp/veritae-server.jar.new"
echo "EC2로 복사 완료, 교체·재시작합니다."

"$SSH" "${SSH_OPTS[@]}" "$HOST" "sudo bash -s -- $COMMIT" <<'REMOTE'
set -euo pipefail
COMMIT="$1"
APP=/opt/veritae
NEW=/tmp/veritae-server.jar.new

# 인증 없이 호출하면 401이 와야 정상 - 서버가 떠서 요청을 처리하고 있다는 뜻이다.
wait_until_up() {
    for _ in $(seq 1 24); do
        code="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/v1/members/me || true)"
        if [[ "$code" == "401" ]]; then
            return 0
        fi
        sleep 5
    done
    return 1
}

if [[ -f "$APP/veritae-server.jar" ]]; then
    cp -p "$APP/veritae-server.jar" "$APP/veritae-server.jar.prev"
fi
install -o veritae -g veritae -m 664 "$NEW" "$APP/veritae-server.jar"
rm -f "$NEW"
systemctl restart veritae

if wait_until_up; then
    echo "$COMMIT $(date '+%Y-%m-%d %H:%M:%S %Z')" > "$APP/DEPLOYED_COMMIT"
    chown veritae:veritae "$APP/DEPLOYED_COMMIT"
    echo "배포 성공: $COMMIT"
    exit 0
fi

echo "새 버전이 2분 안에 응답하지 않아 이전 버전으로 되돌립니다. 원인은 서버에서 'journalctl -u veritae -n 100'으로 확인하세요." >&2
if [[ -f "$APP/veritae-server.jar.prev" ]]; then
    install -o veritae -g veritae -m 664 "$APP/veritae-server.jar.prev" "$APP/veritae-server.jar"
    systemctl restart veritae
    if wait_until_up; then
        echo "이전 버전으로 복구했습니다." >&2
    else
        echo "이전 버전도 응답하지 않습니다. 서버를 직접 확인하세요." >&2
    fi
fi
exit 1
REMOTE
