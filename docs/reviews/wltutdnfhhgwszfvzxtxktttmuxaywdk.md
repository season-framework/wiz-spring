# WIZ Spring 구조/성능 보완 리뷰 반영 기록

- Review ID: `wltutdnfhhgwszfvzxtxktttmuxaywdk`
- 대상: `wiz-spring`, `wiz-spring-instruction`, 기본 Java sample project template
- 반영 범위: socket 인증/전송 기본값, runtime cache 성능, DTO 기본 가이드, SSE/실시간 통신 경계 문서화

## 결정 요약

실시간 통신은 core가 최소 공통 실행 표면을 제공하고, 프로젝트별 SSE, broker, multi-instance broadcast 구현은 각 프로젝트 source/dependency에서 선택하는 방향으로 정리했다.

기본 `wiz.socket()`은 성능을 위해 native WebSocket을 사용한다. 기존 Socket.IO HTTP polling은 호환 경로로 남긴다.

WIZ의 자유로운 response 형식은 유지하되, 새 scaffold와 guide는 고정 응답에 record/class DTO를 기본으로 사용하도록 바꿨다. `Map`은 동적 key가 필요한 자유 형식 payload에만 권장한다.

## 반영한 보완점

### P0. 실시간 통신 인증/권한 경계

반영:

- `SocketSession`에 `HttpSession`, cookie snapshot, remote address를 포함했다.
- native WebSocket handshake와 Socket.IO polling session 생성 시 기존 Servlet `HttpSession`을 연결한다.
- `ProjectSocketDispatcher`가 project `socket.java` handler를 호출하기 전에 app metadata의 `controller` 정책을 `ControllerChain`으로 먼저 실행한다.
- `controller: "user"` 또는 `"admin"`인 app socket은 HTTP API/route와 같은 session/auth guard를 통과해야 한다.
- native WebSocket 인증 실패는 policy violation close로 종료하고, Socket.IO polling은 실패 result를 반환한다.

남은 경계:

- room/session registry는 여전히 단일 JVM memory adapter다. 운영 scale-out은 Redis pub/sub, broker, managed realtime service 등을 프로젝트 또는 별도 adapter로 선택해야 한다.

### P0. 기본 `wiz.socket()` 전송 방식

반영:

- 기본 Angular helper의 `wiz.socket()`을 Socket.IO client 의존성 없이 native WebSocket(`/wiz/ws/app/{project}/{app_id}`)으로 연결하도록 바꿨다.
- 기존 Socket.IO polling(`/socket.io/`, namespace `/wiz/app/{project}/{app_id}`)은 호환 경로로 유지했다.
- default template과 sample package에서 `socket.io-client` 의존성을 제거했다.
- README와 instruction은 native WebSocket이 기본, Socket.IO polling은 호환용이라고 설명하도록 수정했다.

### P0. runtime cache hit 파일 순회 비용

반영:

- production profile에서는 `bundle/.wiz-build.json` marker 기반 version을 우선 사용해 runtime cache hit마다 compiled artifact tree를 반복 순회하지 않도록 줄였다.
- dev profile은 기존처럼 artifact fingerprint를 함께 반영해 hot reload 감지 범위를 유지한다.

남은 경계:

- request 단위 runtime reference 재사용, `WatchService` 기반 dev invalidation, config namespace cache는 별도 개선 과제로 남는다.

### P1. SSE 표면

결정:

- core에 `SseController`나 `wiz.sse()` 같은 공통 구현을 지금 추가하지 않는다.
- SSE는 프로젝트 요구가 다양하므로 instruction에서 구현 방향만 제공한다.

가이드:

- 진행률, 알림, 빌드 로그처럼 server-to-client 단방향 stream은 실제 프로젝트 route/controller source에서 Spring MVC `SseEmitter` 또는 별도 WebFlux endpoint로 구현한다.
- heartbeat, reconnect id, send timeout, max connection, overflow/drop policy, 사용자별 stream 권한은 프로젝트 코드에서 명시한다.
- multi-instance broadcast가 필요하면 Redis pub/sub, message broker, managed realtime service 등을 project dependency/source에서 선택한다.

### P1. Java compile-time safety와 response 형식

반영:

- 자유로운 `Map` response는 유지한다.
- 새 app scaffold의 기본 API 예시는 record DTO를 반환한다.
- instruction과 guide는 고정 응답에 record/class DTO를 우선 사용하고, 자유 형식 payload에만 `Map`을 쓰도록 수정했다.

남은 경계:

- app handler naming, model namespace, socket payload는 여전히 convention/string 기반이다.
- request DTO binding, build-time signature validation, generated accessor는 별도 설계 과제로 남는다.

## 계속 추적할 구조 리스크

- `ProjectBuildService`, `WizMcpToolService`는 여전히 큰 책임을 가진 클래스다. build phase와 MCP tool group 단위 분리가 필요하다.
- project-local Spring/JPA context는 core Spring context와 분리되어 있어 metrics/health/transaction 관측성 연결이 제한적이다.
- static asset cache 정책, prod origin/body/session limit template, socket/session metrics는 운영 기본값 강화 과제로 남는다.
- 긴 연결 soak test, proxy timeout/upgrade 설정 검증, multi-instance broadcast 검증은 별도 환경에서 수행해야 한다.
