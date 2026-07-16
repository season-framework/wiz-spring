# Architecture

## Runtime

`wiz-spring`은 CLI와 Spring server가 하나의 executable jar에 들어있는 구조다.

구성:

- CLI: Picocli command (`create`, `build`, `jar`, `run`, `bundle`, `service`, `kill`, `mcp`, `completion`)
- Build: source reconstruct, Java compile, workspace dependency copy, Angular build, bundle manifest
- HTTP: static/SPA, App API dispatcher, Route dispatcher
- Runtime facade: `WizContext`, `WizRequest`, `WizResponse`, `SessionService`, `AuthService`, `ModelRegistry`
- Socket: 기본 native WebSocket handler와 Socket.IO polling 호환 endpoint

## Runtime cache

요청마다 현재 workspace bundle metadata와 classpath를 기준으로 dispatch한다. build marker가 바뀌면 cache가 폐기되고 새 class가 사용된다.

## Extension boundary

core는 실행 기반만 제공한다. DB, ORM, SSO, SMTP, domain service는 app source와 workspace dependency로 구현한다.

## Build layout

WIZ source of truth는 workspace `src/**`에 남긴다. build phase는 WIZ source를 내부 staging `build/.wiz/source`로 평탄화한 뒤, 외부에서 보이는 Java project surface를 `build/pom.xml`, `build/src/main/java`, `build/src/main/resources`, `build/target/**` 형태로 생성한다. runtime은 이 compile 결과를 다시 `bundle/classes`, `bundle/app-api.jar`, `bundle/src/**`로 복사해 실행한다.
