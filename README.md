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

두 이미지를 한 번에 build하려면 `./build.sh all`을 사용합니다. 기본 image tag는 runtime이 `registry.nanoha.kr/kwon3286/wiz-spring:0.2.5`, bind가 `registry.nanoha.kr/kwon3286/wiz-spring:0.2.5-bind`입니다. 아래 환경 변수로 값을 바꿀 수 있습니다.

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
jar=/root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.2.5.jar
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

package root를 바꾸려면 언제든 `build --package`를 사용합니다. 설정, Maven groupId와 WIZ Java source의 package 참조가 함께 변경되고 기존 generated Spring tree와 bundle을 제거하는 clean build가 자동 적용됩니다. 이미 배포용 standalone JAR을 만들었다면 package 변경 후 다시 패키징해야 합니다.

```bash
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
| `build --root <path> [--package <package>] [--clean] [--phase reconstruct\|compile\|bundle]` | source 재구성, Java compile, frontend build/fallback, bundle 생성을 수행합니다. `--package`는 package root를 변경하고 자동으로 clean build합니다. |
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
    application-dev.yml
    application-prod.yml
    application.example.yml
    application-dev.example.yml
    application-prod.example.yml
    wiz.yml
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
- `config/application.yml`, `config/application-<profile>.yml`: 로컬 서버와 앱 runtime 설정. 생성된 `.gitignore`의 보호 대상입니다.
- `config/application*.example.yml`: 비밀 값 없이 공유하는 설정 예시. Git에는 이 파일들을 커밋합니다.
- `config/wiz.yml`: Java workspace 형식과 metadata schema, 기준 `wiz-spring` 버전을 기록하는 판별용 marker입니다.

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

## 설정 파일과 profile

`config/`의 설정은 다음 순서로 합쳐집니다. 뒤에서 읽은 값이 같은 key를 덮어씁니다.

1. `wiz-spring` core 기본값
2. workspace `config/application.yml`
3. 선택된 profile의 `config/application-<profile>.yml`
4. `wiz-spring run`의 `--host`, `--port`, `--profile` 같은 명시적 CLI 옵션

| 파일 | 읽는 경우 |
| --- | --- |
| `application.yml` | 모든 실행에서 공통으로 먼저 읽습니다. |
| `application-dev.yml` | `dev` profile일 때 읽습니다. `wiz-spring run`과 `service regist`로 만든 서비스의 기본 profile이 `dev`입니다. |
| `application-prod.yml` | `prod` profile일 때 읽습니다. 인자 없이 실행하는 standalone app jar의 기본 profile이 `prod`입니다. `wiz-spring run --profile prod`로도 선택할 수 있습니다. |
| `application-<name>.yml` | `wiz-spring run --profile <name>`으로 해당 profile을 선택했을 때 읽습니다. 파일이 없어도 공통 설정만으로 실행됩니다. |
| `application*.example.yml` | runtime은 읽지 않습니다. Git에 공유하기 위한 예시 파일입니다. |

Spring의 typed runtime 설정과 workspace Java 코드의 `wiz.config().namespace("application")`은 모두 위 profile 병합 결과를 사용합니다. 여러 profile이 활성화되면 나중 profile이 앞 profile의 같은 key를 덮어씁니다.

새 workspace의 `application.yml`에는 workspace마다 결정되는 `server.port`, `wiz.java.package-root`와 공통 session cookie 보안 정책만 활성 값으로 생성됩니다. 사용처 없는 값이나 core 기본값과 같은 API/HTTP/socket/redirect/runtime 설정은 중복해서 쓰지 않습니다. 환경별 `Secure` 정책은 `application-dev.yml`과 `application-prod.yml`에 분리합니다.

Flask의 기본 client-side session과 달리 WIZ Spring은 Servlet container의 server-side `HttpSession`을 사용합니다. browser cookie에는 session data나 암호화한 사용자 정보가 아니라 임의의 session ID(`JSESSIONID`)만 들어가므로 별도 `wiz.secret`은 생성하거나 읽지 않습니다. 기본 저장소는 process memory이므로 재시작 후 session 유지나 여러 instance 간 공유가 필요하면 Spring Session의 Redis/JDBC 같은 공용 저장소를 구성해야 합니다.

| session 설정 | 적용 값 |
| --- | --- |
| 공통 `application.yml` | cookie tracking only, `HttpOnly=true`, `SameSite=Lax` |
| `application-dev.yml` | 로컬 HTTP 개발을 위해 `Secure=false` |
| `application-prod.yml` | HTTPS 전용으로 `Secure=true` |

prod profile의 로그인/session 기능은 HTTPS를 전제로 합니다. cross-site cookie가 꼭 필요한 경우에만 `SameSite=None`과 `Secure=true`를 함께 사용하고, timeout/name/path/domain 변경은 `server.servlet.session.*`에서 관리합니다. logout도 이 실제 Servlet 설정을 읽어 동일한 이름·domain·path의 cookie를 만료시킵니다.

실제 파일에는 이후 DB/API credential 같은 환경별 값이 추가될 수 있으므로 `wiz-spring create`는 `application.yml`, `application-*.yml`을 `.gitignore`에 넣고, 처음부터 비밀 값이 없는 `application*.example.yml`을 함께 생성합니다. 팀에 공유할 변경은 example 파일에도 직접 반영하세요.

clone한 workspace에서는 필요한 example을 실제 설정으로 복사한 뒤 로컬 값을 채웁니다.

```bash
cp config/application.example.yml config/application.yml
cp config/application-dev.example.yml config/application-dev.yml
# 운영 배포 환경에서만 필요할 때:
cp config/application-prod.example.yml config/application-prod.yml
```

`build`, `bundle`, `jar`는 Git 추적 여부와 무관하게 실제 `config/` 내용을 산출물에 복사합니다. 특히 standalone jar에는 설정이 포함되므로 배포 전에 민감 값 포함 여부와 artifact 접근 권한을 확인하세요.

## 주요 설정

```yaml
server:
  port: 3000
  servlet:
    session:
      tracking-modes:
        - cookie
      cookie:
        http-only: true
        same-site: lax

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

`config/wiz.yml`은 runtime 설정 파일이 아니며 다음과 같은 판별 metadata만 가집니다.

```yaml
workspace: "java"
format-version: 1
runtime:
  name: "wiz-spring"
  version: "0.2.5"
```

`runtime.version`은 workspace를 생성한 `wiz-spring` 실행 파일의 버전입니다. 개발 classpath에서 직접 실행해 manifest version이 없으면 `dev`로 기록됩니다.

`0.2.2`로 생성한 기존 workspace를 업그레이드할 때는 [`release-log/0.2.4.md`](release-log/0.2.4.md)의 config migration 절차를 따르세요. 기존 source를 다시 생성하지 않고 session 설정, `wiz.yml`, Git ignore/example 파일만 custom 값과 병합합니다. 첫 build 이후 package 변경이 필요하면 [`release-log/0.2.5.md`](release-log/0.2.5.md)의 절차를 추가로 확인하세요.

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
