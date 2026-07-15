# WIZ Spring Runtime

WIZ Spring은 WIZ 앱을 Java 21/Spring Boot로 실행하는 runtime/CLI입니다.
현재 구조는 하나의 workspace 안에 하나의 앱 source만 두는 방식입니다. 여러 앱을 만들거나 전환하는 명령과 별도 하위 디렉터리 계층은 사용하지 않습니다.

## 요구 사항

- Java/JDK 21 이상
- Maven Wrapper 사용 가능 환경
- Angular frontend를 실제 빌드할 경우 Node.js 20.19 이상 또는 22.12 이상

## Docker 개발환경

`wiz-base`와 같은 방식으로 일반 실행 이미지와 workspace 영속화용 bind 이미지를 만들 수 있습니다. 이미지에는 Java 21 JDK, Maven 3.9, Node.js 22, SSH/기본 개발 도구, WIZ Spring runtime과 미리 build된 sample workspace가 포함됩니다. 이 저장소와 같은 상위 디렉터리의 `wiz-spring-instruction` 내용은 `/opt/app/.github`에 복사됩니다. Spring runtime 자체의 MCP를 사용하므로 구형 `wiz-vscode` extension MCP와 `wiz-track-apt`는 포함하지 않습니다.

일반 이미지를 build하고 실행하려면:

```bash
./build.sh runtime
./run.sh

curl http://127.0.0.1:3334/actuator/health
docker exec -it wiz-spring-test bash
```

workspace를 host에 영속화하려면 bind target을 사용합니다. 최초 실행 시 image의 `/opt/app` seed가 `DATA_ROOT/app`으로 복사되고 이후에는 해당 데이터를 그대로 사용합니다.

```bash
./build.sh bind
./run-bind.sh

# 기본 저장 위치: ./.wiz-data-bind/app
```

두 이미지를 한 번에 build하려면 `./build.sh all`을 사용합니다. 기본 image tag는 runtime이 `registry.nanoha.kr/kwon3286/wiz-spring:0.2.3`, bind가 `registry.nanoha.kr/kwon3286/wiz-spring:0.2.3-bind`입니다. 아래 환경 변수로 값을 바꿀 수 있습니다.

- Build: `IMAGE`, `VERSION`, `PLATFORM`, `WIZ_PACKAGE_ROOT`, `WIZ_SPRING_INSTRUCTION_DIR`, `INSTALL_CODEX`, `CODEX_VERSION`
- Run: `CONTAINER_NAME`, `HOST_HTTP_PORT`, `HOST_SSH_PORT`, `CONTAINER_HTTP_PORT`, `WIZ_ENABLE_SSH`
- Bind run: 위 run 변수와 `DATA_ROOT`

Codex CLI는 기본으로 설치되고 `/opt/app/.codex`에는 standalone WIZ Spring MCP 설정이 생성됩니다. Codex CLI가 필요 없는 image는 다음처럼 만들 수 있습니다.

```bash
INSTALL_CODEX=false ./build.sh runtime
```

`wiz-spring-instruction`이 다른 위치에 있으면 named build context 경로를 지정합니다.

```bash
WIZ_SPRING_INSTRUCTION_DIR=/path/to/wiz-spring-instruction ./build.sh runtime
```

SSH는 password 인증을 허용하지 않습니다. 공개키 로그인이 필요하면 실행 시 키를 전달합니다.

```bash
SSH_PUBLIC_KEY="$(cat ~/.ssh/id_ed25519.pub)" ./run.sh
ssh -p 2223 root@127.0.0.1
```

## Runtime 빌드

```bash
cd /root/workspace/wiz-java/wiz-spring
./mvnw clean package
```

빌드 결과는 `target/wiz-spring-*.jar`입니다.

## 앱 생성

```bash
jar=/root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.2.3.jar
workspace=/tmp/demo2

rm -rf "$workspace"
java -jar "$jar" create "$workspace" --package a.b.c
```

`--package`는 필수이며, 생성/빌드되는 Java package root가 됩니다. 위 예시는 build 결과를 `build/src/main/java/a/b/c/...`와 `build/target/classes/a/b/c/...` 아래에 만듭니다. 기본 sample로 생성한 workspace의 `devlog.md`는 표 헤더만, `devlog/`는 빈 디렉터리로 초기화됩니다. `--path`나 `--uri`로 가져온 source의 기존 devlog 이력은 그대로 보존됩니다.

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

초기 build에서 create 때 지정한 package root를 바꾸려면 create의 자동 build를 건너뛴 뒤 `build --package`를 사용합니다. 설정, Maven groupId와 WIZ Java source의 package 참조가 함께 변경되며 package 변경 build는 자동으로 clean 처리됩니다. 성공한 bundle build의 `bundle/.wiz-build.json`이 생긴 뒤에는 package를 다시 변경할 수 없습니다.

```bash
java -jar "$jar" create "$workspace" --package com.example.bootstrap --skip-build
java -jar "$jar" build --root "$workspace" --package com.example.product
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
| `build --root <path> [--package <package>] [--clean] [--phase reconstruct\|compile\|bundle]` | source 재구성, Java compile, frontend build/fallback, bundle 생성을 수행합니다. `--package`는 첫 성공 bundle build 전에만 package root를 변경합니다. |
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
  .github/
    copilot-instructions.md
  config/
    application.yml
  devlog/
  devlog.md
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
