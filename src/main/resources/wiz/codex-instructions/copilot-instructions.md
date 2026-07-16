# WIZ Spring Copilot Instructions

이 문서는 WIZ Spring 프로젝트에서 Copilot/Codex가 따라야 할 기준이다. 기존 WIZ 원본 런타임 전제 대신 현재 `wiz-spring` 구현을 기준으로 작성한다.

## Runtime 기준

- 런타임은 Java 21 이상, Spring Boot 4.0.6, Maven Wrapper, Picocli CLI로 구성한다.
- 실행 단위는 Spring Boot executable jar다. 런타임 자체를 스크립트 패키지처럼 설치하지 않는다.
- 프로젝트 백엔드는 Java source를 빌드해 bundle에 포함한다. 동적 서버 스크립트 실행을 전제로 설계하지 않는다.
- 프론트엔드는 기존 WIZ의 `view.pug`, `view.ts`, `view.scss`, `app.json`, portal asset 패턴을 유지한다.
- `build/`, `bundle/`, `target/`, `node_modules/`, `.angular/`는 생성 산출물이다. 수정은 `src/`, `config/`, `pom.xml`, `src/angular/package.json`에서 시작한다.
- `build/`는 공개 Spring Boot/Maven build 산출물로 `pom.xml`, `src/main/java`, `src/main/resources`, `target/classes`, `target/dependency`, `target/app-api.jar`, `target/frontend`를 가진다. WIZ 내부 staging은 `build/.wiz/source` 아래에만 둔다.

## Source of truth

```text
workspace/
  config/
    application.yml
    application-dev.yml
    application-prod.yml
    application.example.yml
    application-dev.example.yml
    application-prod.example.yml
    wiz.yml
  src/
    angular/
    app/<app_id>/
      app.json
      view.pug
      view.ts
      view.scss
      api.java
      socket.java
    controller/*Controller.java
    model/**/*.java
    route/<route_id>/
      app.json
      route.java
    portal/<package>/
      app/
      controller/
      model/
      route/
      libs/
      assets/
  pom.xml
  build/
  bundle/
```

## Backend 작성 규칙

- App API는 `src/app/{app_id}/api.java`에 작성하고 `/wiz/api/{app_id}/{function}`으로 호출한다.
- API class는 `public final class PageXyzApi` 형태를 기본으로 한다. 다른 class를 쓸 때는 `app.json.api.handler`에 FQCN을 명시한다.
- API method는 `WizContext`를 인자로 받고 `WizResult` 또는 plain object를 반환한다. 명시적 status/header/cookie/download/redirect가 필요하면 `WizResult`를 반환한다.
- Controller hook은 `ControllerHook`을 구현하고 `before(WizContext)`에서 허용 시 `null`, 차단 시 `WizResult`를 반환한다.
- Route는 `RouteHandler`를 구현하고 `route.java`에 둔다. metadata는 `app.json.route`, `methods`, `handler`를 사용한다.
- Socket은 `SocketController`를 구현한다. 기본 frontend `wiz.socket()`은 native WebSocket endpoint `wiz.socket.path/{app_id}`를 사용하며 기본값은 `/wiz/app/{app_id}`다. Socket.IO polling 호환 namespace도 같은 `wiz.socket.path/{app_id}`를 사용하고 HTTP transport만 `/socket.io/`를 거친다. socket connect/event는 app metadata의 `controller` 정책을 먼저 통과한다.

## Request/Response 원칙

- `wiz.request().query()`는 query string, form-urlencoded body, top-level JSON body를 병합한다. 같은 key는 query string이 우선한다.
- 필수 값은 `wiz.request().queryRequired("name")`를 사용해 400 envelope를 반환시킨다.
- JSON 원문 값은 `wiz.request().json()` 또는 `json("name")`로 읽는다.
- 응답은 `return wiz.response().status(code, data)` 또는 `return wiz.response().ok(data)`처럼 반환한다. 구조가 고정된 응답은 record/class DTO를 우선 사용하고, key가 동적인 payload만 `Map`으로 둔다.
- `wiz.response().data(key, value)`는 controller가 후속 응답 envelope에 추가할 공통 data bag을 구성할 때 사용한다.
- Servlet session cookie는 `server.servlet.session.*`으로만 설정한다. 공통 정책은 cookie-only/HttpOnly/SameSite=Lax이고 dev는 `Secure=false`, prod는 `Secure=true`다. 별도 `season.yml` cookie 설정을 만들지 않는다.

## Model/Struct 원칙

- 공통 domain 진입점은 `src/model/Struct.java`에 둔다.
- 앱 모델은 `wiz.models().get("struct", Struct.class)`처럼 type-safe하게 가져온다.
- DB/ORM, SMTP, 외부 SDK는 core가 아니라 workspace `pom.xml`과 app source에 둔다.
- sample은 JPA/Hibernate와 SQLite를 `src/portal/season/model/orm` 및 workspace `pom.xml`로 제공한다.
- 서버 시작 전에 준비해야 할 비용 큰 resource는 `Struct.warmup(WizContext)`에 명시한다. warmup은 idempotent해야 하며 DB pool/JPA metadata, seed, SDK/cache preload처럼 첫 요청 전에 공통으로 필요한 것만 수행한다.
- warmup을 "가능한 모든 작업 실행"으로 설계하지 않는다. 사용자/session 의존 작업, 긴 batch, 전체 데이터 scan, non-idempotent 외부 호출은 제외하고 필요하면 readiness/smoke test로 검증한다.
- runtime cache와 생명주기가 맞는 장기 resource는 `wiz.projectRuntime().onClose(...)`로 닫고, 운영 관측이 필요한 resource는 `wiz.observability()`로 health, gauge, transaction/timer metric을 등록한다.

## 설정과 의존성

- 런타임 공통 의존성은 `wiz-spring/pom.xml`에 추가한다.
- 앱 Java 의존성은 workspace `pom.xml`에 추가하고 build 시 `build/target/dependency`에 반영한다.
- frontend 의존성은 `src/angular/package.json`에 둔다.
- runtime 설정 우선순위는 core 기본값, workspace `config/application.yml`, 선택된 `application-<profile>.yml`, CLI option 순서다. 같은 key는 뒤의 값이 우선한다.
- `wiz-spring run`과 `service regist`로 만든 서비스는 기본 `dev`, 인자 없이 실행하는 standalone app jar는 기본 `prod` profile이다. 다른 profile은 `wiz-spring run --profile <name>`으로 선택한다.
- Spring typed 설정과 `wiz.config().namespace("application")`은 같은 active profile 파일을 병합해 읽는다.
- 실제 `config/application.yml`, `application-*.yml`은 생성된 `.gitignore` 대상이다. 비밀 값이 없는 `application*.example.yml`만 커밋하고, clone 후 필요한 example을 실제 파일명으로 복사한다.
- 비밀 값이나 환경별 credential을 example 파일에 넣거나 application 설정의 ignore 규칙을 임의로 제거하지 않는다. `jar`와 `bundle`에는 실제 config가 포함되므로 배포 artifact도 점검한다.
- `wiz-spring build --package <package>`는 build 이력과 관계없이 package 설정, workspace pom과 WIZ source 참조를 변경하고 clean build한다. 첫 build 이후라는 이유로 거부하지 않는다.
- `wiz.runtime.warmup-enabled`는 기본 `true`이며, `Struct.warmup(WizContext)` hook을 서버 시작 시 호출한다.
- WIZ runtime은 source/config 변경을 watch하지 않는다. `src/`, `config/`, `pom.xml`, `src/angular/package.json`을 수정한 뒤에는 반드시 `wiz-spring build --root <workspace> --clean`으로 `bundle/.wiz-build.json` marker를 갱신한 다음 실행/검증한다.

## CLI

`wiz-spring create`는 기본 template, `--path`, `--uri` 모두 `.codex` MCP 설정과 내장 `.github` 인스트럭션을 자동으로 설치한다. 별도 `codex` 하위 명령은 사용하지 않는다.

```bash
java -jar target/wiz-spring-0.2.6.jar --help
java -jar target/wiz-spring-0.2.6.jar create <workspace> --package com.example.demo
java -jar target/wiz-spring-0.2.6.jar build --root <workspace> --clean
java -jar target/wiz-spring-0.2.6.jar build --root <workspace> --package com.example.renamed
java -jar target/wiz-spring-0.2.6.jar jar --root <workspace> --output /tmp/demo.jar
java -jar target/wiz-spring-0.2.6.jar run --root <workspace> --port 3000
source <(java -jar target/wiz-spring-0.2.6.jar completion bash)
```

## 검증


WIZ Spring은 기존 WIZ 프로젝트 구조를 유지하되 서버 런타임을 Java 21, Spring Boot 4.0.6, Maven 기반 실행 jar로 제공한다.
프로젝트 백엔드는 `api.java`, `route.java`, `socket.java`, `src/controller/*.java`, `src/model/**/*.java`가 source of truth이고,
`build/`와 `bundle/`은 `wiz-spring build`가 매번 재생성하는 산출물이다.

기본 개발 흐름:

```bash
cd /root/workspace/wiz-java/wiz-spring
./mvnw clean package

jar=/root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.2.6.jar
workspace=/tmp/wiz-spring-demo

rm -rf "$workspace"
java -jar "$jar" create "$workspace" --package com.example.demo
java -jar "$jar" build --root "$workspace" --clean
java -jar "$jar" run --root "$workspace" --port 3000
```

검증 명령:

```bash
cd /root/workspace/wiz-java
bash scripts/e2e-spring-smoke.sh
bash scripts/contract-spring-http.sh

cd /root/workspace/wiz-java/wiz-spring
./mvnw test
```


작업을 마칠 때는 수정한 문서에 legacy backend 실행 전제가 남지 않았는지 `rg`로 확인한다.
