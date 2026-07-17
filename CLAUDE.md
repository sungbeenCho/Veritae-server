# Veritae 프로젝트

AI 기반 디지털 사기 예방 및 콘텐츠 신뢰도 분석 플랫폼

## 팀 구성
- iOS 개발: 팀장(형) — https://github.com/IMCHO/Veritae-iOS
- 서버(Spring Boot): 나 (사용자)

## 환경 메모
- JAVA_HOME 수동 설정 완료 (빌드 실패 시 이것부터 의심하지 말 것)

## 레포
- 서버: https://github.com/sungbeenCho/Veritae-server (main 브랜치)
- 로컬 경로: C:\Users\sb112\IdeaProjects\veritae-server
- iOS: https://github.com/IMCHO/Veritae-iOS
- 워크플로우 플러그인: https://github.com/IMCHO/Veritae-workflow

## 워크플로우 활용 (참고용, 작업 성격에 맞을 때만)
Claude Code 마켓플레이스 플러그인 (10개 역할 기반 서브에이전트) — 설치되어 있음:
planner, designer, api-architect, ios-dev, spring-dev, qa,
convention-checker, ios-reviewer, spring-reviewer, api-contract-reviewer

- API 설계처럼 규모 있는 작업엔 `@api-architect` 등 활용 고려
- 사소한 수정/조회까지 서브에이전트를 반사적으로 쓸 필요는 없음
