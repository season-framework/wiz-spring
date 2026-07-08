# WIZ Spring Runtime

WIZ Spring은 WIZ 앱을 Java 21/Spring Boot로 실행하는 runtime/CLI입니다.
현재 구조는 하나의 workspace 안에 하나의 앱 source만 두는 방식입니다. 여러 앱을 만들거나 전환하는 명령과 별도 하위 디렉터리 계층은 사용하지 않습니다.

## 요구 사항

- Java/JDK 21 이상
- Maven Wrapper 사용 가능 환경
- Angular frontend를 실제 빌드할 경우 Node.js 20.19 이상 또는 22.12 이상

## Runtime 빌드

```bash
cd /root/workspace/wiz-java/wiz-spring
./mvnw clean package
```

빌드 결과는 `target/wiz-spring-*.jar`입니다.

## 앱 생성

```bash
jar=/root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.2.1.jar
workspace=/tmp/demo2

rm -rf "$workspace"
java -jar "$jar" create "$workspace" --package a.b.c
```

`--package`는 필수이며, 생성/빌드되는 Java package root가 됩니다. 위 예시는 build 결과를 `build/src/main/java/a/b/c/...`와 `build/target/classes/a/b/c/...` 아래에 만듭니다.

기본 생성은 sample source를 만들고 clean bundle build까지 실행합니다. 생성만 하려면:

```bash
java -jar "$jar" create "$workspace" --package a.b.c --skip-build
```

## 실행과 배포

```bash
java -jar "$jar" run --root "$workspace" --port 3000
```

다시 빌드:

```bash
java -jar "$jar" build --root "$workspace" --clean
```

단일 실행 jar 패키징:

```bash
java -jar "$jar" jar --root "$workspace" --output /tmp/demo2.jar
java -jar /tmp/demo2.jar
```

runtime bundle 디렉터리 생성:

```bash
java -jar "$jar" bundle --root "$workspace" --output /tmp/demo2-bundle
```

## Command Reference

| Command | 용도 |
| --- | --- |
| `create <path> --package <package>` | 단일 workspace를 생성하고 기본 sample source를 배치합니다. 기본적으로 clean build까지 실행합니다. |
| `build --root <path> [--clean] [--phase reconstruct\|compile\|bundle]` | source 재구성, Java compile, frontend build/fallback, bundle 생성을 수행합니다. |
| `run --root <path> [--host <host>] [--port <port>] [--profile <profile>]` | WIZ Spring 서버를 실행합니다. 기본 profile은 `dev`입니다. |
| `jar --root <path> [--clean] [--skip-build] [--output <jar>]` | workspace bundle을 포함한 단일 실행 jar를 만듭니다. |
| `bundle --root <path> [--output <dir>]` | 이미 build된 bundle과 config를 배포용 디렉터리로 복사합니다. |
| `kill [--dry-run]` | 실행 중인 `wiz-spring run` 프로세스를 찾거나 종료합니다. |
| `service ...` | Linux/systemd 서비스 등록, 삭제, 조회, 시작, 중지를 처리합니다. |
| `mcp --root <path> [--state <file>]` | WIZ Spring MCP stdio 서버를 실행합니다. |
| `codex --root <path> --runtime-jar <jar> [--check\|--force]` | workspace `.codex` MCP 설정을 생성하거나 검사합니다. |

`project create`, `project build`, `project jar`, `project list` 같은 multi-project 명령은 더 이상 사용하지 않습니다.

## Workspace 구조

```text
demo2/
  config/
    application.yml
  src/
    app/
    controller/
    model/
    portal/
    route/
    angular/
  build/
  bundle/
  pom.xml
```

- `src/**`: WIZ source
- `build/**`: 외부 공유 시 열어볼 수 있는 Spring Boot/Maven 표준형 build 산출물
- `bundle/**`: runtime이 읽는 실행 산출물
- `pom.xml`: 앱 Java dependency
- `config/application.yml`: 서버와 앱 runtime 설정

`build/`는 아래처럼 정리됩니다.

```text
build/
  pom.xml
  src/main/java/
  src/main/resources/
  target/classes/
  target/dependency/
  target/app-api.jar
  target/frontend/
  .wiz/source/
```

`build/src/main/java`, `build/src/main/resources`, `build/target/**`는 외부에서 보아도 일반 Spring Boot/Maven project에 가까운 공개 산출물입니다. `build/.wiz/source`는 WIZ app, portal, Angular 입력을 평탄화한 내부 staging 경로이며 직접 수정하지 않습니다.

## 주요 설정

```yaml
server:
  port: 3000

wiz:
  java:
    package-root: a.b.c
  api:
    prefix: /wiz/api
  socket:
    path: /wiz/app
    allowed-origins:
      - "*"
  runtime:
    devmode-cookie-name: season-wiz-devmode
    warmup-enabled: true
```

`wiz.api.prefix`, `wiz.socket.path`는 배포 환경에 맞게 `/wiz`를 숨기거나 다른 prefix로 바꿀 수 있습니다.
Angular frontend는 build 단계에서 이 값을 `wiz-runtime-config.ts`로 편입합니다. 이 값을 바꾼 뒤에는 frontend bundle을 다시 빌드하세요.

## Source 규칙

- App API: `src/app/{appId}/api.java`
- App socket: `src/app/{appId}/socket.java`
- Route: `src/route/{routeId}/route.java`
- Controller hook: `src/controller/*.java`
- Model/Struct: `src/model/**/*.java`
- Portal package: `src/portal/{package}/...`

Source 파일에 package 선언이 없으면 build 단계에서 `wiz.java.package-root` 기준 package가 자동으로 붙습니다.
build 산출물의 Java package는 Spring 계층형 명칭을 사용합니다.

| Source | Generated package |
| --- | --- |
| `src/app/{appId}/api.java` | `{packageRoot}.web.api.{AppId}Api` |
| `src/app/{appId}/socket.java` | `{packageRoot}.realtime.socket.{AppId}SocketController` |
| `src/route/{routeId}/route.java` | `{packageRoot}.web.route.{RouteId}RouteHandler` |
| `src/controller/**/*.java` | `{packageRoot}.security.guard...` |
| `src/model/Struct.java` | `{packageRoot}.application.model.Struct` |
| `src/model/struct/**/*.java` | `{packageRoot}.application.service...` |
| `src/model/db/**/*.java` | `{packageRoot}.domain.entity...` |
| `src/portal/{package}/model/**/*.java` | `{packageRoot}.module.{package}.application|domain|infrastructure...` |

## MCP와 Codex

WIZ Spring MCP 서버는 runtime jar 안에 포함되어 있습니다.

```bash
java -jar "$jar" mcp --root "$workspace"
java -jar "$jar" codex --root "$workspace" --runtime-jar "$jar"
```

MCP 도구는 workspace/source/package/app 기준 이름을 사용합니다. 예: `wiz_workspace_status`, `wiz_app_build`, `wiz_app_jar`, `wiz_app_dependency_info`, `wiz_source_create_app`, `wiz_package_create`.

## 테스트

```bash
./mvnw test
```

Angular `platformBrowser()` 전환 후 실제 브라우저에서 WIZ render와 routerLink까지 확인하려면:

```bash
node scripts/verify-angular-platform-browser.mjs
```

이 검증은 임시 workspace에 전용 probe 페이지를 주입해 `service.render()` 이후 부모 변수, `@Input`, `@Output` DOM 갱신과 주요 routerLink 이동을 확인합니다. Playwright는 `/tmp` 아래 도구 디렉터리에 설치됩니다.
