# WIZ Spring Runtime

Python WIZ `2.5.2` runtime을 기준으로 Java Spring 기반으로 포팅한 WIZ runtime/CLI입니다. 현재 포팅은 기존 WIZ의 workspace 생성, project 생성/빌드/실행, Java sample project runtime 동작을 Spring Boot 실행 파일 하나로 제공하는 것을 목표로 합니다.

[English version](#english)

## 빠른 시작

먼저 runtime jar를 빌드합니다.

```bash
cd /root/workspace/wiz-java/wiz-spring
./mvnw clean package
```

실행 파일은 `target/wiz-spring-*.jar`에 생성됩니다.

```bash
jar=/root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.0.7.jar
workspace=/tmp/wiz-spring-demo

rm -rf "$workspace"
java -jar "$jar" create "$workspace"
java -jar "$jar" project create --root "$workspace" --project main
java -jar "$jar" run --root "$workspace" --port 3000
```

프로젝트를 외부 workspace 없이 실행되는 단일 jar로 묶으려면 `project jar`를 사용합니다. 생성된 jar는 내장된 project bundle을 사용자 cache에 풀고 바로 서버를 시작하므로, 배포 시 외부에 필요한 jar는 하나입니다.

```bash
java -jar "$jar" project jar --root "$workspace" --project main --output /tmp/wiz-main.jar
java -jar /tmp/wiz-main.jar
```

`project jar`는 실행 jar 옆에 `/tmp/wiz-main.jar.sha256` 형식의 SHA-256 checksum 파일도 생성합니다.

`project create`는 기본적으로 jar에 내장된 Java sample project를 생성한 뒤 clean bundle build까지 수행합니다. 생성만 하고 싶으면 `--skip-build`를 붙입니다.

```bash
java -jar "$jar" project create --root "$workspace" --project main --skip-build
```

자주 쓸 때는 shell alias를 두면 편합니다.

```bash
alias wiz-spring='java -jar /root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.0.7.jar'

wiz-spring create ./demo
wiz-spring project create --root ./demo --project main
wiz-spring run --root ./demo --port 3000
```

alias로 사용할 때 `wiz-spring --help`와 `wiz-spring --version`도 `wiz-spring` 이름을 기준으로 표시됩니다.

## 요구 버전

| 항목 | 요구/기준 버전 |
| --- | --- |
| Python WIZ baseline | `2.5.2` |
| Java runtime | Java `21` 이상 |
| Project build | JDK `21` 이상. `api.java`, `route.java`, `socket.java`, model/controller Java source를 build 중 컴파일합니다. |
| Spring Boot | `4.0.6` |
| Maven | Maven Wrapper 기준 Apache Maven `3.9.15`. project `pom.xml` dependency 해석에도 Maven CLI를 사용합니다. |
| Picocli | `4.7.7` |
| Commons Compress | `1.26.2` |
| Node.js | Angular CLI 21 기준 `^20.19.0`, `^22.12.0`, 또는 `>=24.0.0` |
| npm | Node.js에 포함된 npm 사용. 현재 검증 환경은 npm `11.12.1`입니다. |
| Angular sample | Angular/Angular CLI `21.0.0` 이상, TypeScript `5.9.x` |

Maven은 runtime jar를 source에서 빌드할 때와, project `pom.xml`에 Java dependency가 있는 project를 build할 때 필요합니다. 이미 dependency가 `project/<name>/target/dependency` 또는 `project/<name>/lib`에 준비되어 있고 frontend real build가 필요 없다면 실행 시에는 Java만으로도 동작합니다.

## 패키지와 의존성 설치

Python WIZ의 `pip install season`처럼 runtime 자체를 Python package로 설치하는 구조가 아니라, WIZ Spring은 Spring Boot 실행 jar를 빌드해서 사용합니다.

| 대상 | 정의 파일 | 설치/반영 방법 |
| --- | --- | --- |
| WIZ Spring runtime Java/Spring dependency | `wiz-spring/pom.xml` | dependency를 추가한 뒤 `./mvnw clean package`로 jar를 다시 빌드합니다. |
| Project frontend/npm dependency | `project/<name>/src/angular/package.json` | `wiz-spring project npm install --project=<name> --package=<pkg>` 또는 직접 `package.json` 수정 후 `wiz-spring project build --clean`으로 npm dependency를 다시 설치합니다. |
| Project Java API/model/controller dependency | `project/<name>/pom.xml` | `wiz-spring project build`가 `mvn dependency:copy-dependencies`를 실행해 `target/dependency` jar를 준비하고, Java compile/runtime classpath에 포함합니다. 직접 jar를 둘 경우 `project/<name>/lib`도 인식합니다. |
| Project Spring/runtime config | `project/<name>/config/application.yml`, `application-dev.yml`, `application-prod.yml` | `wiz-spring run` 시작 시 workspace `config/application.yml` 다음으로 로드됩니다. `server.port`, datasource, project extension class, dev/prod profile 설정 등 project별 설정을 여기에 둡니다. |

runtime의 핵심 정의 파일은 [`pom.xml`](pom.xml)입니다. 여기에는 Spring Boot, WebMVC, WebSocket, picocli 같은 core runtime dependency만 둡니다. JPA/Hibernate, SMTP, project별 SDK처럼 app/package마다 달라지는 dependency는 각 project의 `pom.xml`에 둡니다. Angular sample dependency는 내장 sample의 `src/angular/package.json`에 있고, clean build 시 `npm ci` 또는 `npm install` 후 Angular CLI `ng build`를 실행합니다. normal build는 기존 `build/src/angular/node_modules`를 보존하고 npm install을 건너뜁니다.

## CLI 지원 범위

현재 CLI command 이름은 `wiz-spring`입니다. shell alias 없이 jar를 직접 실행할 때는 `java -jar target/wiz-spring-*.jar ...` 형태로 호출합니다.

| 기능 | Spring port 지원 |
| --- | --- |
| `wiz-spring create [path]` | 지원. workspace를 만들고 `config/`, `project/`를 생성합니다. 웹 IDE용 `public/`, `ide/`, `plugin/` root는 만들지 않습니다. |
| `wiz-spring project create` | 지원. `--project main`, local path import, git URI clone, `.wizproject` zip import를 지원합니다. 기본 생성 후 clean build가 자동 실행됩니다. |
| `wiz-spring project build` | 지원. `--clean --phase bundle` 형태로 Java compile, Pug/Angular build, bundle 생성을 수행합니다. Java 버전, frontend install/build 로그와 단계별 시간을 그대로 출력합니다. normal build는 npm install을 건너뜁니다. |
| `wiz-spring project list` | 지원. workspace의 project 목록을 출력합니다. |
| `wiz-spring project delete` | 지원. 지정 project를 삭제합니다. |
| `wiz-spring project export` | 지원. project를 `.wizproject` archive로 내보냅니다. |
| `wiz-spring run` | 지원. `--root --host --port --project --profile`로 Spring server를 시작합니다. 기본 profile은 `dev`, 기본 host는 `0.0.0.0`, 기본 포트는 `3000`이며, `--host`/`--port`가 없으면 workspace/project `application.yml` 값으로 override할 수 있습니다. `--bundle`, `--log` compatibility option도 받습니다. `--log`는 서버 stdout/stderr를 지정 파일에 함께 기록하므로 project API의 `System.out.println`도 확인할 수 있습니다. |
| `wiz-spring bundle` | 지원. 이미 build된 project bundle을 deploy/runtime bundle directory로 묶습니다. |
| `wiz-spring kill` | 지원. Spring WIZ `run` process만 대상으로 종료하며 `--dry-run`을 지원합니다. |
| `wiz-spring project app list/create/delete` | 지원. `src/app` 및 portal app skeleton을 생성/삭제합니다. |
| `wiz-spring project controller list/create/delete` | 지원. Java controller hook skeleton을 생성/삭제합니다. |
| `wiz-spring project route list/create/delete` | 지원. `route.java` skeleton을 생성/삭제합니다. |
| `wiz-spring project package list/create/delete` | 지원. portal package를 생성/삭제하고 목록을 출력합니다. |
| `wiz-spring project npm list/install/uninstall` | 지원. `src/angular/package.json` 기준 npm dependency를 조회/설치/삭제합니다. |
| `wiz-spring mcp` | 지원. Spring WIZ 전용 MCP stdio 서버로 실행되며 workspace/project/source/package 도구를 Spring WIZ 프로젝트 구조에 맞게 제공합니다. |
| `wiz-spring codex` | 지원. workspace `.codex/config.toml`, `.codex/AGENTS.md`를 Spring MCP 기준으로 생성/검사합니다. 기존 파일이 다르면 경고하고, `--force`로 덮어쓸 수 있습니다. |
| `wiz-spring service list/regist/unregist/status/start/stop/restart` | 지원. Linux/systemd service command입니다. `list`는 표 형식으로 systemd/binary/root/port/log를 출력하고, `config` port는 가능한 경우 실제 설정 포트로 해석합니다. `regist --dry-run`으로 생성물을 미리 볼 수 있습니다. |
| `wiz-spring server` | 별도 CLI command로는 미지원. Spring server 실행은 `run`으로 통합했습니다. |
| IDE/plugin 관리 | 미지원. 현재 포팅 범위는 IDE가 아니라 runtime/build/run입니다. |
| Python backend 자동 실행/자동 변환 | 미지원. WIZ Spring은 Java project source를 실행 대상으로 하며 Python source migration/stub 생성은 포함하지 않습니다. |

### MCP 서버

WIZ Spring MCP 서버는 `wiz-vscode` 확장 코드와 분리된 Spring runtime jar 내부 기능입니다. backend source는 `api.java`, `route.java`, `socket.java`, `src/controller`, `src/portal` 규칙으로 처리합니다. Python virtualenv/pip 관리 도구는 Spring 프로젝트에서 사용하지 않으므로 도구 목록에 노출하지 않고, 대신 standalone `project jar`, Java controller 생성/삭제, portal package 삭제, project dependency 위치 확인 도구를 제공합니다. 상태 파일은 기본적으로 workspace의 `.wiz/mcp-state.json`에 저장하며, `--state`로 명시 위치를 지정할 수 있습니다.

```bash
java -jar target/wiz-spring-0.0.7.jar mcp --root /path/to/workspace --project main
```

MCP client 설정에서는 서버 이름을 `wiz-spring`으로 두고, `command`를 `java`, `args`를 `["-jar", "/path/to/wiz-spring-0.0.7.jar", "mcp", "--root", "/path/to/workspace"]`처럼 지정합니다. VS Code에서 사용할 경우에도 별도 extension code가 아니라 이 jar 명령을 직접 가리키면 됩니다.

Codex 설정은 `wiz-spring codex`로 생성할 수 있습니다. 기존 `.codex/config.toml` 또는 `.codex/AGENTS.md`가 생성 기준과 다르면 기본적으로 경고와 종료 코드 `2`를 반환하고, `--force`를 주면 덮어씁니다.

```bash
java -jar target/wiz-spring-0.0.7.jar codex --root /path/to/workspace --project main
java -jar target/wiz-spring-0.0.7.jar codex --root /path/to/workspace --project main --force
java -jar target/wiz-spring-0.0.7.jar codex --root /path/to/workspace --project main --check
```

## Runtime 지원 범위

Spring runtime은 다음 기능을 지원합니다.

| 영역 | 지원 내용 |
| --- | --- |
| Workspace/project 탐색 | Spring workspace marker(`config/application.yml` 또는 `config/wiz.yml`, `project/`)를 인식합니다. |
| Static/SPA | `bundle/www` 정적 파일과 SPA fallback을 제공합니다. |
| App API | `/wiz/api/{app_id}/{function}` 요청을 project-local `api.java` method로 dispatch합니다. |
| Request facade | query string, form-urlencoded body, top-level JSON body 값을 `wiz.request().query()`에서 읽을 수 있습니다. JSON body는 `json()`/`json(name)`으로도 접근합니다. |
| Response facade | JSON status envelope, redirect, download, header, cookie set/delete를 지원합니다. |
| Controller hook | built-in `base`, `user`, `admin` controller와 project-local Java controller hook을 지원합니다. |
| Route | `src/route/*/app.json` metadata와 `route.java` handler dispatch를 지원합니다. |
| Config | project source에서 `config/*.yml` namespace load와 compatibility key normalization을 지원합니다. Spring server 설정은 workspace `config/application.yml`과 project `config/application.yml`에서 override할 수 있습니다. |
| Session/Auth | core는 session/auth facade와 user/admin guard 기본 구현만 제공합니다. 실제 구현은 project `src/model/SessionService.java`, `src/model/AuthService.java` convention 또는 `application.yml`의 `wiz.session.service-class`, `wiz.auth.service-class`로 덮어쓸 수 있습니다. 기존 project 호환을 위해 `src/model/session`, `src/model/auth`, `src/session`, `src/auth`도 fallback으로 읽습니다. `/auth/check`, `/auth/logout` endpoint는 기본 sample project의 route source가 정의하며, `/auth/check`는 비로그인도 HTTP `200`으로 응답하고 body의 `data.status`로 인증 여부를 표현합니다. |
| Model/Struct | core는 `wiz.models()`와 `src/model`, `src/portal/{portal}/model` convention만 제공합니다. 기본 sample의 DB 공통 설정은 `src/portal/season/model/orm`, password helper는 `src/portal/season/model/security`에 숨겨져 있고, app/domain 쪽은 entity 내부 `Repository`를 가져와 쓰는 구조입니다. |
| Portal package backend | PWA route, SMTP, ORM 같은 portal/package backend 구현은 core가 아니라 project source에 둡니다. 기본 sample은 `/sw.js` route와 JPA/Hibernate sample ORM을 project-local source와 `project pom.xml` dependency로 제공합니다. |
| Socket | 기본 frontend `wiz.socket()`은 native WebSocket endpoint(`/wiz/ws/app/{project}/{app_id}`)로 연결됩니다. Spring core는 기존 client 호환을 위해 Socket.IO v4 HTTP long-polling handshake(`/socket.io/`, namespace `/wiz/app/{project}/{app_id}`)도 유지합니다. socket connect/event는 app metadata의 `controller` 정책(`base`, `user`, `admin`, project-local controller)을 먼저 통과한 뒤 project-local `socket.java` handler로 dispatch됩니다. socket handler는 message dispatch마다 현재 bundle을 읽으므로, `socket.java` 수정 후 `project build`가 끝나면 서버 재시작 없이 다음 메시지/연결부터 새 handler가 반영됩니다. |
| Build marker | `bundle/.wiz-build.json`에 build phase, Java/runtime version, frontend mode, artifact mtime, dependency digest를 기록합니다. |

## Project 생성과 import

기본 Java sample project는 jar 내부 resource(`/wiz/templates/default-project-java/`)에서 생성됩니다. source는 압축 파일이 아니라 풀린 디렉터리와 manifest(`/wiz/templates/default-project-java.files`)로 관리하므로 git diff에서 변경 내용을 바로 확인할 수 있습니다. 따라서 별도의 `wiz-sample-project-java` 디렉토리가 없어도 됩니다.

기본 sample에는 `/chat` 페이지가 포함되어 있으며, `view.ts`는 기존 WIZ 패턴대로 `wiz.socket()`을 사용합니다. `src/app/page.chat/socket.java`는 native WebSocket(`/wiz/ws/app/{project}/page.chat`)과 Socket.IO polling 호환 namespace(`/wiz/app/{project}/page.chat`)를 통해 간단한 lobby 채팅을 제공합니다.

기본 Java sample project:

```bash
java -jar "$jar" project create --root "$workspace" --project main
```

local project import:

```bash
java -jar "$jar" project create --root "$workspace" --project imported --path /path/to/wiz-project
```

`.wizproject` archive import:

```bash
java -jar "$jar" project create --root "$workspace" --project imported --path /path/to/project.wizproject
```

git repository import:

```bash
java -jar "$jar" project create --root "$workspace" --project imported --uri https://github.com/example/wiz-project.git
```

`--uri`는 `https://`, `http://`, `ssh://`, `git@...` 형태만 허용합니다. local path와 `file://` URI는 clone 대상으로 허용하지 않습니다. zip import는 zip-slip, absolute path, symlink entry, `.git`, 과도한 entry/size를 차단합니다.

Python WIZ project 자동 변환은 현재 범위가 아닙니다. 기존 project를 가져올 때는 Java Spring project 구조(`api.java`, `route.java`, `socket.java`, `src/model`, project `pom.xml`, project `config/application.yml`)로 정리된 source를 import 대상으로 사용합니다.

## Project build

```bash
java -jar "$jar" project build --root "$workspace" --project main --clean
```

build는 기본 `bundle` phase로 실행됩니다.

| Phase | 내용 |
| --- | --- |
| `reconstruct` | WIZ source를 `build/src`로 재구성합니다. |
| `java-source` | app/route/socket/model/controller Java source를 package-aware source tree로 재작성합니다. |
| `project-dependencies` | project `pom.xml`이 있으면 Maven dependency를 `target/dependency`로 복사합니다. |
| `java-compile` | `api.java`, `route.java`, `socket.java`, model/controller Java source를 컴파일합니다. |
| `bundle` | frontend build/fallback 결과와 compiled Java artifact를 bundle로 묶습니다. |

bundle build가 성공하면 공급망 추적 산출물이 함께 생성됩니다.

| Artifact | 내용 |
| --- | --- |
| `project/<name>/bundle/.wiz-build.json` | runtime version, project name, build phases, dependency digest |
| `project/<name>/bundle/.wiz-dependencies.json` | bundle `lib/*.jar`, `project-api.jar`, `pom.xml`의 SHA-256 manifest |
| `project/<name>/target/bom.json` | bundle runtime dependency 기준 CycloneDX JSON SBOM |
| `project/<name>/target/<name>.jar.sha256` | `project jar`로 만든 standalone jar checksum |

`src/angular/package.json`이 있으면 project-local Angular CLI package로 판단합니다. clean build는 lockfile이 있으면 `npm ci`, 없으면 `npm install`을 실행한 뒤 `node_modules/.bin/ng build`를 실행합니다. normal build는 `build/src/angular/node_modules`를 보존하고 npm install을 건너뛰며, dependency가 없으면 `--clean` build를 요구합니다. Angular 21 sample은 별도 `ngc-esbuild` pipeline이 아니라 Angular CLI 내장 esbuild builder(`@angular-devkit/build-angular:browser-esbuild`)를 사용합니다. Angular 입력이 없거나 real build가 불가능하면 `frontend-fallback`으로 최소 web bundle을 생성합니다.

## App-local Java API

Backend code는 app frontend file 옆에 둡니다.

```text
project/main/src/app/page.xyz/
  app.json
  view.ts
  view.pug
  view.scss
  api.java
```

예시:

```java
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;

public final class PageXyzApi {
    public WizResult status(WizContext wiz) {
        return wiz.response().status(200, wiz.request().query("text", "hello"));
    }
}
```

package declaration이 없으면 build 중 `com.wiz.project.{project}.api.PageXyzApi` 형태로 rewrite됩니다. 기본 파일명은 `api.java`이지만 `PageXyzApi.java`처럼 handler class 이름의 app-local Java 파일도 컴파일 대상으로 인식합니다. `app.json.api.handler`에 명시적인 handler class를 지정할 수도 있습니다.

## Project-local 설정과 확장

`wiz-spring run`은 다음 순서로 Spring 설정을 읽습니다.

1. core jar의 기본 `application.yml`
2. workspace `config/application.yml`
3. 선택된 project의 `config/application.yml`
4. 활성 profile에 맞는 `application-dev.yml` 또는 `application-prod.yml`
5. CLI option `--host`, `--port`, `--project`, `--profile`

`wiz-spring run`은 기본 profile을 `dev`로 두고, standalone project jar를 인자 없이 실행하면 기본 profile을 `prod`로 둡니다. `wiz-spring run --profile prod`처럼 명시하면 해당 Spring profile이 active profile로 적용됩니다. 따라서 포트, datasource, 외부 API key, auth/session 구현체 class 같은 값은 project마다 다르게 둘 수 있습니다.

`wiz.project.cookie-selection-enabled`는 dev profile에서 기본 `true`, prod/standalone jar profile에서 기본 `false`입니다. 운영에서 여러 project를 한 runtime에 노출해야 할 때는 cookie 전환을 켜는 대신 host/path 기반 routing과 project별 auth/tenant ACL을 별도 경계로 설계하세요.

`wiz.project.warmup-enabled`는 기본 `true`입니다. 서버 시작 시 선택된 기본 project의 선택적 `Struct.warmup(WizContext)` hook을 호출해, 기본 sample처럼 JPA/Hikari pool과 seed data를 첫 로그인 전에 초기화할 수 있습니다. 외부 DB를 서버 기동과 분리해야 하는 환경에서는 `false`로 끌 수 있습니다.

```yaml
server:
  port: 3001
  tomcat:
    threads:
      max: 200
      min-spare: 10
    accept-count: 100
    max-connections: 8192

sample:
  datasource:
    url: jdbc:postgresql://localhost:5432/example
    driver-class-name: org.postgresql.Driver
    username: wiz
    password: change-me
    maximum-pool-size: 10
    minimum-idle: 2
    connection-timeout-millis: 30000

wiz:
  project:
    warmup-enabled: true
  auth:
    service-class: com.example.project.auth.CustomAuthService
  session:
    service-class: com.example.project.session.CustomSessionService
  socket:
    allowed-origins:
      - "*"
    polling-session-ttl-millis: 120000
    max-polling-sessions: 1024
    polling-queue-capacity: 256
  redirect:
    policy: any # any | local-only | allowlist
    allowed-hosts: []
```

`wiz.socket.allowed-origins`는 기본값 `["*"]`로 개발 서버 호환성을 유지하며, 제한이 필요할 때 WebSocket과 `/socket.io/` polling origin을 같은 값으로 검사합니다. Socket.IO polling session은 idle TTL, 최대 session 수, session별 outbound queue 크기를 설정할 수 있어 장시간 미종료 연결이나 느린 consumer로 인한 메모리 누적을 제한합니다. `wiz.redirect.policy`도 기본값 `any`로 기존 logout redirect를 유지하고, 프로젝트가 원할 때만 `local-only` 또는 `allowlist`로 좁힙니다. Spring Security로 endpoint를 기본 차단하지 않으며, 운영 보안 경계는 nginx/apache2 같은 앞단 웹서버에서 통일하는 것을 권장합니다.

기본 sample의 JPA helper는 project runtime cache와 연동해 project/config 단위로 `EntityManagerFactory`를 재사용합니다. bundle marker가 바뀌어 project runtime cache가 폐기되면 연결된 JPA context도 함께 닫히므로, 요청마다 Hibernate factory를 새로 만들지 않습니다.

`wiz-spring`은 Spring Boot 내장 Tomcat의 worker thread가 요청을 처리하므로 단일 Flask 개발 서버처럼 CPU core 하나에 고정되는 구조가 아닙니다. JVM process 안의 여러 worker thread가 OS scheduler를 통해 여러 core에서 실행됩니다. 다만 동시 처리량은 Tomcat thread 수, DB connection pool 크기, 외부 I/O latency, project code의 lock 사용에 의해 제한됩니다. 기본 sample은 HikariCP를 사용하며 SQLite는 write lock 특성 때문에 기본 pool size를 1로 둡니다. 수십~수백 명 규모의 write-heavy 운영은 PostgreSQL/MySQL 같은 서버형 DB와 적정 pool size로 전환하세요.

package declaration이 없는 기본 project source는 `src/model/AuthService.java`가 `com.wiz.project.{project}.model.AuthService`, `src/model/SessionService.java`가 `com.wiz.project.{project}.model.SessionService`로 build됩니다. 이 convention을 쓰면 `application.yml`에 class 이름을 쓰지 않아도 자동으로 로드됩니다. 기존 project 호환용으로 `src/model/auth`, `src/model/session`, `src/auth`, `src/session` 위치도 계속 fallback 로드됩니다.

## Route와 Socket Java source

Route handler:

```text
project/main/src/route/custom.echo/
  app.json
  route.java
```

`route.java`는 `com.wiz.dispatch.RouteHandler`를 구현합니다. package declaration이 없으면 `com.wiz.project.{project}.route.CustomEchoRouteHandler`로 rewrite됩니다.

Socket handler:

```text
project/main/src/app/page.xyz/socket.java
```

package declaration이 없으면 예를 들어 `page.xyz`는 `com.wiz.project.{project}.socket.PageXyzSocketController`로 rewrite됩니다. 이 class는 project build 산출물이며 core jar에는 포함되지 않습니다. `app.json.socket.handler`로 handler class를 지정할 수 있습니다.

## 검증

```bash
cd /root/workspace/wiz-java
bash scripts/e2e-spring-smoke.sh
bash scripts/contract-spring-http.sh

cd /root/workspace/wiz-java/wiz-spring
./mvnw test
```

최근 검증 기준:

- `./mvnw test`: 161 tests
- `scripts/contract-spring-http.sh`: 15 contract tests
- `scripts/e2e-spring-smoke.sh`: Java sample create/build/run smoke

## 참고 문서

- [Apache Maven documentation](https://maven.apache.org/guides/index.html)
- [Spring Boot Maven Plugin Reference](https://docs.spring.io/spring-boot/4.0.6/maven-plugin)
- [Spring Web MVC reference](https://docs.spring.io/spring-boot/4.0.6/reference/web/servlet.html)
- [Spring Boot Actuator reference](https://docs.spring.io/spring-boot/4.0.6/reference/actuator/index.html)
- [Spring validation reference](https://docs.spring.io/spring-boot/4.0.6/reference/io/validation.html)

## English

WIZ Spring Runtime is a Java Spring port based on the Python WIZ `2.5.2` runtime. It currently focuses on the CLI/workspace/project build/run path and the Java sample project runtime behavior.

### Quick Start

```bash
cd /root/workspace/wiz-java/wiz-spring
./mvnw clean package

jar=/root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.0.7.jar
workspace=/tmp/wiz-spring-demo

rm -rf "$workspace"
java -jar "$jar" create "$workspace"
java -jar "$jar" project create --root "$workspace" --project main
java -jar "$jar" run --root "$workspace" --port 3000
```

To package a project as a single externally deployed jar, use `project jar`. The packaged jar extracts its embedded project bundle to the user cache and starts the server when launched without arguments.

```bash
java -jar "$jar" project jar --root "$workspace" --project main --output /tmp/wiz-main.jar
java -jar /tmp/wiz-main.jar
```

`project jar` also writes `/tmp/wiz-main.jar.sha256` next to the standalone jar.

`project create` uses the Java sample project embedded in the jar and runs an initial clean bundle build by default. Use `--skip-build` to scaffold/import sources only.

### Requirements

| Component | Version |
| --- | --- |
| Java/JDK | `21` or later |
| Spring Boot | `4.0.6` |
| Maven Wrapper | Apache Maven `3.9.15` |
| Picocli | `4.7.7` |
| Node.js | `^20.19.0`, `^22.12.0`, or `>=24.0.0` for Angular CLI 21 |
| Angular sample | Angular/CLI `21.0.0` or later, TypeScript `5.9.x` |

### Dependencies

WIZ Spring is distributed as a Spring Boot jar, not as a Python package. Core runtime dependencies live in `wiz-spring/pom.xml`; add Java/Spring libraries there only when the runtime itself needs them. Project Java dependencies live in `project/<name>/pom.xml` and are resolved into `target/dependency` during `wiz-spring project build`. Frontend dependencies live in each project at `project/<name>/src/angular/package.json`; use `wiz-spring project npm install` or edit the file and run `wiz-spring project build --clean` when dependencies need to be installed again. Normal project builds preserve `build/src/angular/node_modules` and skip npm install. Project Spring/runtime settings live in `project/<name>/config/application.yml`, `application-dev.yml`, and `application-prod.yml`; `wiz-spring run` defaults to the `dev` profile, while standalone project jars default to `prod`. `wiz.project.warmup-enabled` defaults to `true` and calls an optional `Struct.warmup(WizContext)` hook for the default project during startup.

Successful project bundle builds also write `bundle/.wiz-dependencies.json`, `bundle/.wiz-build.json` dependency digest fields, and `target/bom.json` CycloneDX metadata. `project jar` writes `<name>.jar.sha256` next to the standalone jar. Runtime Maven package builds write `target/bom.json` through the CycloneDX Maven plugin. Archive-handling dependencies such as `commons-compress` should be treated as security-priority updates.

### Command Coverage

With the `wiz-spring` alias, supported commands are `create`, `run`, `bundle`, `kill`, `service`, `mcp`, `codex`, `project create`, `project build`, `project list`, `project delete`, `project export`, `project app`, `project controller`, `project route`, `project package`, and `project npm`. Separate `wiz-spring server`, web IDE, plugin management, and Python backend auto-conversion/execution are outside the Spring port scope.

`wiz-spring mcp` runs the standalone Spring MCP server. MCP client settings should use the server name `wiz-spring` and run `java -jar /path/to/wiz-spring-0.0.7.jar mcp --root /path/to/workspace --project main`. `wiz-spring codex --root /path/to/workspace --project main` generates `.codex/config.toml` and `.codex/AGENTS.md`; it warns with exit code `2` when existing files differ, and `--force` overwrites them. `wiz-spring service list` prints systemd services as a table and resolves configured ports when possible. `wiz-spring run --log <file>` tees server stdout/stderr into the file, including project API `System.out.println` output.

### Runtime Coverage

The runtime supports static SPA serving from `bundle/www`, app-local Java APIs, Java route handlers, controller hooks, config/session/auth facades, project-local model conventions, build markers, native WebSocket `wiz.socket()` connections over `/wiz/ws/app/{project}/{app_id}`, and Socket.IO HTTP long-polling compatibility over `/socket.io/`. App-local Java APIs can use either the conventional `api.java` file or a handler-named source file such as `PageAccessApi.java`. Prefer record/class DTOs for fixed API response shapes, and keep `Map` responses for intentionally free-form payloads. JPA/Hibernate/PWA/SMTP-style portal package backends belong to project source, not the core jar. Auth/session implementations can be supplied by project classes under `src/model/AuthService.java`, `src/model/SessionService.java`, or configured in project `application.yml`. Socket handlers reuse the same app controller policy as HTTP APIs/routes before dispatch and are loaded from the current bundle for each dispatch, so rebuilding a changed `socket.java` is enough for the next message/connection to use the new handler without restarting the Spring server.
