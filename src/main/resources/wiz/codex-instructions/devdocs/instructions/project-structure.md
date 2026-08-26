# Project Structure

## Workspace

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
  pom.xml
  src/
    angular/
    app/
    controller/
    model/
    route/
    portal/
  build/
  bundle/
  target/
```

`src/`, `config/`, `pom.xml`, `src/angular/package.json`이 source of truth다. `build/`, `bundle/`, `target/`은 생성 산출물이다.

실제 `application.yml`, `application-<profile>.yml`은 로컬 runtime source이지만 비밀 값 보호를 위해 Git에서 제외한다. Git에는 `application*.example.yml`을 공유하고 clone 후 필요한 example을 실제 파일명으로 복사한다. 상세 규칙은 `configuration-profiles.md`를 따른다.

`wiz.yml`은 runtime 설정이 아니라 Java workspace type, metadata format version, 기준 `wiz-spring` version을 기록하는 marker다. 이 파일은 Git에 포함하며 임의의 app 설정을 넣지 않는다.

`build/`는 외부에서 보아도 Spring Boot/Maven project처럼 읽히도록 아래 구조를 사용한다.

```text
build/
  pom.xml
  src/main/java/
  src/main/resources/
  target/classes/
  target/dependency/
  target/app-api.jar
  target/frontend/
  target/work/source/
```

`build/src/main/java`, `build/src/main/resources`와 `build/target/classes`, `dependency`, `app-api.jar`, `frontend`가 공개 build 산출물이다. `build/target/work/source`는 WIZ app/portal/Angular source를 평탄화한 일시적 staging 경로이며 clean build에서 재생성되므로 직접 수정하지 않는다. build lock/runtime snapshot과 MCP 상태는 workspace 밖의 운영체제 runtime/state 경로를 사용하고 npm은 기본 사용자 cache를 사용하므로 프로젝트 내부에 별도 숨김 framework 디렉터리를 만들지 않는다.

## App

```text
src/app/page.dashboard/
  app.json
  view.pug
  view.ts
  view.scss
  api.java
  socket.java
```

`app.json.controller`는 `base`, `user`, `admin`, 또는 custom controller 이름을 지정한다.

## Java package rewrite

source의 package 선언은 유무와 관계없이 build 중 source 위치와 handler metadata에 해당하는 `wiz.java.package-root` 기준 package로 재작성된다. 파일 앞에 license 주석이 있고 그 뒤에 package 선언이 있어도 기존 선언을 교체하며 중복 package 선언을 만들지 않는다.

`create --path`와 `create --uri`는 가져온 설정 및 Java `package`/`import`에서 기존 WIZ package root를 추론한다. 추론된 root는 `--package` 값으로 바꾸고 Java source뿐 아니라 `pom.xml`, config, JSON handler metadata의 같은 참조도 함께 변경한다.

`wiz-spring build --package <package>`는 build 이력과 관계없이 package root 설정과 source/pom 참조를 변경한 뒤 clean build한다. WIZ source가 source of truth이고 generated Spring tree는 다시 생성되므로 첫 build 이후에도 사용할 수 있다.

| Source | Generated package |
| --- | --- |
| `src/app/{id}/api.java` | `{packageRoot}.web.api.{AppId}Api` |
| `src/app/{id}/socket.java` | `{packageRoot}.realtime.socket.{AppId}SocketController` |
| `src/controller/*.java` | `{packageRoot}.security.guard` |
| `src/model/Struct.java` | `{packageRoot}.application.model.Struct` |
| `src/model/struct/**/*.java` | `{packageRoot}.application.service...` |
| `src/model/db/**/*.java` | `{packageRoot}.domain.entity...` |
| `src/route/{id}/route.java` | `{packageRoot}.web.route.{RouteId}RouteHandler` |
| `src/portal/{pkg}/model/**/*.java` | `{packageRoot}.module.{pkg}.application|domain|infrastructure...` |
