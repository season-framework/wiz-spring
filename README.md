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
jar=/root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.0.2.jar
workspace=/tmp/wiz-spring-demo

rm -rf "$workspace"
java -jar "$jar" create "$workspace"
java -jar "$jar" project create --root "$workspace" --project main
java -jar "$jar" run --root "$workspace" --port 3000
```

`project create`는 기본적으로 jar에 내장된 Java sample project를 생성한 뒤 clean bundle build까지 수행합니다. 생성만 하고 싶으면 `--skip-build`를 붙입니다.

```bash
java -jar "$jar" project create --root "$workspace" --project main --skip-build
```

자주 쓸 때는 shell alias를 두면 편합니다.

```bash
alias wiz-spring='java -jar /root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.0.2.jar'

wiz-spring create ./demo
wiz-spring project create --root ./demo --project main
wiz-spring run --root ./demo --port 3000
```

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
| Project frontend/npm dependency | `project/<name>/src/angular/package.json` | `wiz project npm install --project=<name> --package=<pkg>` 또는 직접 `package.json` 수정 후 `wiz project build`를 실행합니다. |
| Project Java API/model/controller dependency | `project/<name>/pom.xml` | `wiz project build`가 `mvn dependency:copy-dependencies`를 실행해 `target/dependency` jar를 준비하고, Java compile/runtime classpath에 포함합니다. 직접 jar를 둘 경우 `project/<name>/lib`도 인식합니다. |
| Project Spring/runtime config | `project/<name>/config/application.yml` | `wiz run` 시작 시 workspace `config/application.yml` 다음으로 로드됩니다. `server.port`, datasource, project extension class 등 project별 설정을 여기에 둡니다. |

runtime의 핵심 정의 파일은 [`pom.xml`](pom.xml)입니다. 여기에는 Spring Boot, WebMVC, WebSocket, picocli 같은 core runtime dependency만 둡니다. JPA/Hibernate, SMTP, project별 SDK처럼 app/package마다 달라지는 dependency는 각 project의 `pom.xml`에 둡니다. Angular sample dependency는 내장 sample의 `src/angular/package.json`에 있고, build 시 `npm ci` 또는 `npm install` 후 Angular CLI `ng build`를 실행합니다.

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
| `wiz run` | 지원. `run --root --host --port --project`로 Spring server를 시작합니다. 기본 host는 `0.0.0.0`, 기본 포트는 `3000`이며, `--host`/`--port`가 없으면 workspace/project `application.yml` 값으로 override할 수 있습니다. `--bundle`, `--log` compatibility option도 받습니다. |
| `wiz bundle` | 지원. 이미 build된 project bundle을 deploy/runtime bundle directory로 묶습니다. |
| `wiz kill` | 지원. Spring WIZ `run` process만 대상으로 종료하며 `--dry-run`을 지원합니다. |
| `wiz project app list/create/delete` | 지원. `src/app` 및 portal app skeleton을 생성/삭제합니다. |
| `wiz project controller list/create/delete` | 지원. Java controller hook skeleton을 생성/삭제합니다. |
| `wiz project route list/create/delete` | 지원. `route.java` skeleton을 생성/삭제합니다. |
| `wiz project package list/create/delete` | 지원. portal package를 생성/삭제하고 목록을 출력합니다. |
| `wiz project npm list/install/uninstall` | 지원. `src/angular/package.json` 기준 npm dependency를 조회/설치/삭제합니다. |
| `wiz service list/regist/unregist/status/start/stop/restart` | 지원. Linux/systemd service command입니다. `regist --dry-run`으로 생성물을 미리 볼 수 있습니다. |
| `wiz server` | 별도 CLI command로는 미지원. Spring server 실행은 `run`으로 통합했습니다. |
| `wiz ide`, plugin 관리 | 미지원. 현재 포팅 범위는 IDE가 아니라 runtime/build/run입니다. |
| Python backend 자동 실행/자동 변환 | 미지원. WIZ Spring은 Java project source를 실행 대상으로 하며 Python source migration/stub 생성은 포함하지 않습니다. |

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
| Socket | 기존 frontend 패턴처럼 `wiz.socket()`이 HTTP(S) namespace(`/wiz/app/{project}/{app_id}`)로 연결됩니다. Spring core는 Socket.IO v4 HTTP long-polling handshake(`/socket.io/`)를 받아 project-local `socket.java` handler로 dispatch합니다. socket handler는 message dispatch마다 현재 bundle을 읽으므로, `socket.java` 수정 후 `project build`가 끝나면 서버 재시작 없이 다음 메시지/연결부터 새 handler가 반영됩니다. |
| Build marker | `bundle/.wiz-build.json`에 build phase, Java/runtime version, frontend mode, artifact mtime을 기록합니다. |

## Project 생성과 import

기본 Java sample project는 jar 내부 resource(`/wiz/templates/default-project-java/`)에서 생성됩니다. source는 압축 파일이 아니라 풀린 디렉터리와 manifest(`/wiz/templates/default-project-java.files`)로 관리하므로 git diff에서 변경 내용을 바로 확인할 수 있습니다. 따라서 별도의 `wiz-sample-project-java` 디렉토리가 없어도 됩니다.

기본 sample에는 `/chat` 페이지가 포함되어 있으며, `view.ts`는 기존 WIZ 패턴대로 `wiz.socket()`을 사용합니다. `src/app/page.chat/socket.java`는 HTTP(S) namespace(`/wiz/app/{project}/page.chat`)를 통해 간단한 lobby 채팅을 제공합니다.

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

`src/angular/package.json`이 있으면 project-local Angular CLI package로 판단합니다. lockfile이 있으면 `npm ci`, 없으면 `npm install`을 실행한 뒤 `node_modules/.bin/ng build`를 실행합니다. Angular 21 sample은 별도 `ngc-esbuild` pipeline이 아니라 Angular CLI 내장 esbuild builder(`@angular-devkit/build-angular:browser-esbuild`)를 사용합니다. Angular 입력이 없거나 real build가 불가능하면 `frontend-fallback`으로 최소 web bundle을 생성합니다.

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

## Project-local 설정과 확장

`wiz run`은 다음 순서로 Spring 설정을 읽습니다.

1. core jar의 기본 `application.yml`
2. workspace `config/application.yml`
3. 선택된 project의 `config/application.yml`
4. CLI option `--host`, `--port`, `--project`

따라서 포트, datasource, 외부 API key, auth/session 구현체 class 같은 값은 project마다 다르게 둘 수 있습니다.

```yaml
server:
  port: 3001

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/example

wiz:
  auth:
    service-class: com.example.project.auth.CustomAuthService
  session:
    service-class: com.example.project.session.CustomSessionService
```

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

- `./mvnw test`: 124 tests
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

jar=/root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.0.2.jar
workspace=/tmp/wiz-spring-demo

rm -rf "$workspace"
java -jar "$jar" create "$workspace"
java -jar "$jar" project create --root "$workspace" --project main
java -jar "$jar" run --root "$workspace" --port 3000
```

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

WIZ Spring is distributed as a Spring Boot jar, not as a Python package. Core runtime dependencies live in `wiz-spring/pom.xml`; add Java/Spring libraries there only when the runtime itself needs them. Project Java dependencies live in `project/<name>/pom.xml` and are resolved into `target/dependency` during `wiz project build`. Frontend dependencies live in each project at `project/<name>/src/angular/package.json`; use `wiz project npm install` or edit the file and run `wiz project build`. Project Spring/runtime settings live in `project/<name>/config/application.yml` and are loaded by `wiz run` after workspace config.

### Command Coverage

Supported commands are `create`, `run`, `bundle`, `kill`, `service`, `project create`, `project build`, `project list`, `project delete`, `project export`, `project app`, `project controller`, `project route`, `project package`, and `project npm`. Separate `wiz server`, web IDE, plugin management, and Python backend auto-conversion/execution are outside the Spring port scope.

### Runtime Coverage

The runtime supports static SPA serving from `bundle/www`, app-local Java APIs, Java route handlers, controller hooks, config/session/auth facades, project-local model conventions, build markers, and Socket.IO-client-compatible `wiz.socket()` connections over HTTP(S) namespace URLs such as `/wiz/app/{project}/{app_id}` using Engine.IO HTTP long-polling. JPA/Hibernate/PWA/SMTP-style portal package backends belong to project source, not the core jar. Auth/session implementations can be supplied by project classes under `src/model/AuthService.java`, `src/model/SessionService.java`, or configured in project `application.yml`. Socket handlers are loaded from the current bundle for each dispatch, so rebuilding a changed `socket.java` is enough for the next message/connection to use the new handler without restarting the Spring server.
