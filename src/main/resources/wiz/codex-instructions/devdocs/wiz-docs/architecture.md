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

WIZ source of truth는 workspace `src/**`에 남긴다. build phase는 WIZ source를 일시적 staging `build/target/work/source`로 평탄화한 뒤, 외부에서 보이는 Java project surface를 `build/pom.xml`, `build/src/main/java`, `build/src/main/resources`, `build/target/classes`, `build/target/dependency`, `build/target/app-api.jar`, `build/target/frontend` 형태로 생성한다. runtime은 이 compile 결과를 다시 `bundle/classes`, `bundle/app-api.jar`, `bundle/src/**`로 복사해 실행한다.

동시 build를 조정하는 lock은 `WIZ_SPRING_RUNTIME_DIR` 또는 `~/.local/state/wiz-spring/runtime`, 요청 lease용 대용량 runtime snapshot은 `WIZ_SPRING_CACHE_DIR` 또는 `~/.cache/wiz-spring`의 workspace 정규 경로별 외부 저장소에 둔다. 이 디렉터리는 owner-only이며 죽은 process의 snapshot은 다음 기동 때 모든 workspace key에서 정리한다. npm은 기본 사용자 cache를 공유하고 MCP 상태는 `WIZ_SPRING_STATE_DIR`, `XDG_STATE_HOME/wiz-spring`, `~/.local/state/wiz-spring` 순서의 외부 state 경로에 저장한다. 모든 override 경로도 workspace 밖이어야 하므로 workspace에는 숨김 framework 디렉터리를 만들지 않는다.
