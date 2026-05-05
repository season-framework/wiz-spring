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
jar=/root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.0.1-SNAPSHOT.jar
workspace=/tmp/wiz-spring-demo

rm -rf "$workspace"
java -jar "$jar" create "$workspace"
java -jar "$jar" project create --root "$workspace" --project main
java -jar "$jar" run --root "$workspace" --host 127.0.0.1 --port 8080
```

`project create`는 기본적으로 Java sample project를 생성한 뒤 clean bundle build까지 수행합니다. 생성만 하고 싶으면 `--skip-build`를 붙입니다.

```bash
java -jar "$jar" project create --root "$workspace" --project main --skip-build
```

자주 쓸 때는 shell alias를 두면 편합니다.

```bash
alias wiz-spring='java -jar /root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.0.1-SNAPSHOT.jar'

wiz-spring create ./demo
wiz-spring project create --root ./demo --project main
wiz-spring run --root ./demo --port 8080
```

## 요구 버전

| 항목 | 요구/기준 버전 |
| --- | --- |
| Python WIZ baseline | `2.5.2` |
| Java runtime | Java `21` 이상 |
| Project build | JDK `21` 이상. `api.java`, `route.java`, `socket.java`, model/controller Java source를 build 중 컴파일합니다. |
| Spring Boot | `4.0.6` |
| Maven | Maven Wrapper 기준 Apache Maven `3.9.15` |
| Picocli | `4.7.7` |
| SQLite JDBC | `3.49.1.0` |
| Commons Compress | `1.26.2` |
| Node.js | Angular CLI 21 기준 `^20.19.0`, `^22.12.0`, 또는 `>=24.0.0` |
| npm | Node.js에 포함된 npm 사용. 현재 검증 환경은 npm `11.12.1`입니다. |
| Angular sample | Angular/Angular CLI `21.0.0` 이상, TypeScript `5.9.x` |

Maven은 runtime jar를 source에서 빌드할 때만 필요합니다. 이미 빌드된 jar로 workspace를 실행할 때는 Java와, real frontend build가 필요한 경우 Node.js/npm이 있으면 됩니다.

## CLI 지원 범위

현재 실행 command 이름은 `wiz-java`입니다. jar 실행 시에는 `java -jar target/wiz-spring-*.jar ...` 형태로 호출합니다.

| 기존 WIZ 흐름 | Spring port 지원 |
| --- | --- |
| `wiz create [name]` | 지원. `create PATH`가 workspace를 만들고 `config/`, `project/`를 생성합니다. 웹 IDE용 `public/`, `ide/`, `plugin/` root는 만들지 않습니다. |
| `wiz project create` | 지원. `project create --project main`, local path import, git URI clone, `.wizproject` zip import를 지원합니다. 기본 생성 후 clean build가 자동 실행됩니다. |
| `wiz project build` | 지원. `project build --clean --phase bundle` 형태로 Java compile, Pug/Angular build, bundle 생성을 수행합니다. Java 버전, npm install, Angular build 로그와 단계별 시간을 그대로 출력합니다. |
| `wiz project list` | 지원. workspace의 project 목록을 출력합니다. |
| `wiz project delete` | 지원. 지정 project를 삭제합니다. |
| `wiz project export` | 지원. project를 `.wizproject` archive로 내보냅니다. |
| `wiz run` | 지원. `run --root --host --port`로 Spring server를 시작합니다. |
| `wiz server` | 별도 CLI command로는 미지원. Spring server 실행은 `run`으로 통합했습니다. |
| `wiz service` | 별도 daemon/service manager command는 미지원. systemd, Docker, shell script 등 운영 도구에서 packaged jar를 감싸는 방식을 권장합니다. |
| `wiz ide`, plugin 관리 | 미지원. 현재 포팅 범위는 IDE가 아니라 runtime/build/run입니다. |
| Python backend 자동 실행/자동 변환 | 미지원. Python project import 시 migration report와 선택적 `api.java.stub` 생성까지만 지원합니다. |

## Runtime 지원 범위

Spring runtime은 다음 기능을 지원합니다.

| 영역 | 지원 내용 |
| --- | --- |
| Workspace/project 탐색 | Spring workspace와 일부 legacy Python WIZ marker를 인식합니다. |
| Static/SPA | `bundle/www` 정적 파일과 SPA fallback을 제공합니다. |
| App API | `/wiz/api/{app_id}/{function}` 요청을 project-local `api.java` method로 dispatch합니다. |
| Request facade | query string, form-urlencoded body, top-level JSON body 값을 `wiz.request().query()`에서 읽을 수 있습니다. JSON body는 `json()`/`json(name)`으로도 접근합니다. |
| Response facade | JSON status envelope, redirect, download, header, cookie set/delete를 지원합니다. |
| Controller hook | built-in `base`, `user`, `admin` controller와 project-local Java controller hook을 지원합니다. |
| Route | `src/route/*/app.json` metadata와 `route.java` handler dispatch를 지원합니다. |
| Config | `config/*.yml` namespace load와 compatibility key normalization을 지원합니다. |
| Session/Auth | session facade, `/auth/check`, `/auth/logout`, user/admin guard를 지원합니다. `/auth/check`는 비로그인도 HTTP `200`으로 응답하고 body의 `data.status`로 인증 여부를 표현합니다. |
| ORM/Model/Struct | SQLite 기반 sample repository, `wiz.orm()`, `wiz.models()`, struct/model provider convention을 지원합니다. |
| Portal season core | PWA route(`/sw.js`, manifest), season config/session model, SMTP service boundary를 제공합니다. OIDC/SAML은 placeholder 수준입니다. |
| WebSocket | Socket.IO protocol server가 아니라 표준 JSON WebSocket bridge를 지원합니다. endpoint는 `/wiz/ws/app/{project}/{app_id}`입니다. |
| Build marker | `bundle/.wiz-build.json`에 build phase, Java/runtime version, frontend mode, artifact mtime을 기록합니다. |

## Project 생성과 import

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

Python source가 포함된 project를 import하면 migration report를 생성합니다. Java stub이 필요하면 `--java-stubs`를 추가합니다.

```bash
java -jar "$jar" project create --root "$workspace" --project imported --path /path/to/python-wiz-project --java-stubs
```

`--uri`는 `https://`, `http://`, `ssh://`, `git@...` 형태만 허용합니다. local path와 `file://` URI는 clone 대상으로 허용하지 않습니다. zip import는 zip-slip, absolute path, symlink entry, `.git`, 과도한 entry/size를 차단합니다.

## Project build

```bash
java -jar "$jar" project build --root "$workspace" --project main --clean
```

build는 기본 `bundle` phase로 실행됩니다.

| Phase | 내용 |
| --- | --- |
| `reconstruct` | WIZ source를 `build/src`로 재구성합니다. |
| `compile` | `api.java`, `route.java`, `socket.java`, model/controller Java source를 컴파일합니다. |
| `bundle` | frontend build/fallback 결과와 compiled Java artifact를 bundle로 묶습니다. |

`src/angular/package.json`이 있으면 project-local Angular CLI package로 판단합니다. lockfile이 있으면 `npm ci`, 없으면 `npm install`을 실행한 뒤 `node_modules/.bin/ng build`를 실행합니다. Angular 입력이 없거나 real build가 불가능하면 `frontend-fallback`으로 최소 web bundle을 생성합니다.

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

package declaration이 없으면 build 중 `com.wiz.project.{project}.api.PageXyzApi` 형태로 rewrite됩니다. `app.json.api.handler`에 명시적인 handler class를 지정할 수도 있습니다.

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

package declaration이 없으면 `com.wiz.project.{project}.socket.PageXyzSocketController`로 rewrite됩니다. `app.json.socket.handler`로 handler class를 지정할 수 있습니다.

## 검증

```bash
cd /root/workspace/wiz-java
bash scripts/e2e-spring-smoke.sh
bash scripts/contract-spring-http.sh

cd /root/workspace/wiz-java/wiz-spring
./mvnw test
```

최근 검증 기준:

- `./mvnw test`: 119 tests
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

jar=/root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.0.1-SNAPSHOT.jar
workspace=/tmp/wiz-spring-demo

rm -rf "$workspace"
java -jar "$jar" create "$workspace"
java -jar "$jar" project create --root "$workspace" --project main
java -jar "$jar" run --root "$workspace" --host 127.0.0.1 --port 8080
```

`project create` runs an initial clean bundle build by default. Use `--skip-build` to scaffold/import sources only.

### Requirements

| Component | Version |
| --- | --- |
| Java/JDK | `21` or later |
| Spring Boot | `4.0.6` |
| Maven Wrapper | Apache Maven `3.9.15` |
| Picocli | `4.7.7` |
| SQLite JDBC | `3.49.1.0` |
| Node.js | `^20.19.0`, `^22.12.0`, or `>=24.0.0` for Angular CLI 21 |
| Angular sample | Angular/CLI `21.0.0` or later, TypeScript `5.9.x` |

### Command Coverage

Supported commands are `create`, `project create`, `project build`, `project list`, `project delete`, `project export`, and `run`. Separate `wiz server`, `wiz service`, web IDE, plugin management, Socket.IO protocol server compatibility, and arbitrary Python backend auto-conversion/execution are outside the current Spring port scope.

### Runtime Coverage

The runtime supports static SPA serving from `bundle/www`, app-local Java APIs, Java route handlers, controller hooks, config/session/auth facades, SQLite-backed sample ORM/model helpers, PWA routes, build markers, and a standard JSON WebSocket bridge at `/wiz/ws/app/{project}/{app_id}`.
