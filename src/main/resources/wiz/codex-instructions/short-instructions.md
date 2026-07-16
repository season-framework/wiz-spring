# WIZ Spring Short Instructions

## 핵심

- 현재 기준은 `wiz-spring`이다. 서버 코드는 Java/Spring으로 작성한다.
- App backend는 `api.java`, route backend는 `route.java`, socket backend는 `socket.java`다.
- Controller는 `src/controller/*Controller.java`에서 `ControllerHook`을 구현한다.
- Model/Struct는 `src/model/**/*.java`와 `src/portal/{package}/model/**/*.java`에 둔다.
- 서버 시작 전 공통 초기화는 `src/model/Struct.java`의 `public static void warmup(WizContext wiz)`에 둔다.
- warmup은 DB pool/JPA metadata, seed, SDK/cache preload처럼 idempotent하고 공통으로 필요한 작업만 수행한다.
- `build/`, `bundle/`, `target/`은 생성 산출물이며 직접 수정하지 않는다.
- frontend의 `app.json`, `view.pug`, `view.ts`, `view.scss`, `src/angular/**` 패턴은 유지한다.
- `wiz-spring create`는 기본 template, `--path`, `--uri` 모두 `.codex`와 내장 `.github` 인스트럭션을 자동으로 설정한다.

## 요청과 응답

- `wiz.request().query("name", "default")`는 query, form-urlencoded body, top-level JSON body를 병합해 읽는다.
- 필수 값은 `queryRequired("name")`를 사용한다.
- JSON 원문은 `wiz.request().json()`으로 읽는다.
- 응답은 반드시 반환한다: `return wiz.response().status(200, data);`
- 인증 guard는 `wiz.auth().requireUser(wiz)` 또는 `requireAdmin(wiz)`를 반환한다.

## 빌드와 실행

```bash
cd /root/workspace/wiz-java/wiz-spring
./mvnw clean package

java -jar target/wiz-spring-0.2.6.jar create <workspace> --package com.example.demo --skip-build
java -jar target/wiz-spring-0.2.6.jar build --root <workspace> --clean
java -jar target/wiz-spring-0.2.6.jar build --root <workspace> --package com.example.renamed
java -jar target/wiz-spring-0.2.6.jar run --root <workspace> --port 3000
source <(java -jar target/wiz-spring-0.2.6.jar completion bash)
```

`build --package`는 첫 build 전용이 아니다. 언제든 package 설정과 source/pom 참조를 변경하며 package가 달라지면 clean build가 자동 적용된다.

## 설정 profile

- `application.yml`은 공통, `application-dev.yml`은 기본 `wiz-spring run`, `application-prod.yml`은 기본 standalone jar 실행에서 추가로 읽는다.
- 다른 profile은 `wiz-spring run --profile <name>`과 `application-<name>.yml`로 선택한다.
- 실제 `application*.yml`은 Git에서 제외하고 `application*.example.yml`만 공유한다. example에는 비밀 값을 넣지 않는다.
- `jar`와 `bundle`에는 실제 config가 포함되므로 배포 전에 민감 값 포함 여부를 확인한다.
- session cookie는 `server.servlet.session.*`으로 설정한다. dev는 로컬 HTTP용 `Secure=false`, prod는 HTTPS 전용 `Secure=true`다.
- `wiz.yml`은 workspace type, metadata format, `wiz-spring` version 판별용이며 app 설정을 넣지 않는다.

## 검증

```bash
cd /root/workspace/wiz-java
bash scripts/e2e-spring-smoke.sh
bash scripts/contract-spring-http.sh

cd wiz-spring
./mvnw test
```

## 금지

- 생성 산출물만 수정한 뒤 완료 처리하지 않는다.
- application 설정의 `.gitignore` 보호를 제거하거나 example 파일에 credential을 기록하지 않는다.
- 런타임 core에 프로젝트별 DB/SMTP/외부 SDK 의존성을 넣지 않는다.
- warmup을 모든 API/portal 실행, 사용자별 작업, 긴 batch, non-idempotent 외부 호출로 확대하지 않는다.
- legacy backend 파일명이나 프레임워크 전제를 새 문서의 실행 기준으로 쓰지 않는다.
