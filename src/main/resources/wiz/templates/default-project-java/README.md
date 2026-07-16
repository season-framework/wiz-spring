# WIZ Java Sample App

Spring WIZ runtime에서 실행되는 Java backend 샘플 앱입니다. 기존 sample의 frontend/App 구조는 유지하고, backend는 app-local `api.java`, Java controller, Java Struct/domain source로 구성했습니다.

## Demo Accounts

| Email | Password | Name | Role |
| --- | --- | --- | --- |
| admin@example.com | admin1234 | 관리자 | admin |
| alice@example.com | alice1234 | Alice Kim | user |
| bob@example.com | bob12345 | Bob Park | user |
| carol@example.com | carol123 | Carol Lee | editor |
| dave@example.com | dave1234 | Dave Choi | viewer |

The Java Struct layer seeds these accounts and starter posts idempotently. With the default `wiz.runtime.warmup-enabled` setting, this runs during server startup so the first login does not pay the JPA/Hikari initialization cost. The login page also shows the admin sample account so a freshly generated app can be tested immediately.

## Source Layout

```text
src/
  app/
    page.access/api.java
    page.chat/socket.java
    page.dashboard/api.java
    page.members/api.java
    page.mypage/api.java
  controller/
    BaseController.java
    UserController.java
    AdminController.java
  model/
    AuthService.java
    SessionService.java
    Struct.java
    struct/UserStruct.java
    db/UserEntity.java
  portal/post/
    app/list/api.java
    app/detail/api.java
    model/PostStruct.java
    model/struct/PostService.java
    model/struct/CommentService.java
    model/db/PostEntity.java
    model/db/CommentEntity.java
  portal/season/
    model/orm/Jpa.java
    model/orm/JpaConfig.java
    model/orm/Ids.java
    model/security/PasswordHasher.java
```

Frontend files such as `view.pug`, `view.ts`, `view.scss`, `app.json`, `src/angular/**`, and portal frontend assets are preserved from the original sample.

Database access is workspace-local. This sample uses Spring ORM with JPA/Hibernate and SQLite through the workspace `pom.xml`; the runtime core does not contain DB/ORM implementation code. Common DB setup lives under `src/portal/season/model/orm`, and entity-specific repository helpers are nested inside each entity class. The sample JPA runtime registers health, Hikari pool gauges, and transaction duration metrics through `wiz.observability()`, and unregisters them when the project runtime cache is closed. Change `config/application.yml` key `sample.datasource.url` or replace the app entity/helper classes when using another database.

## Run With Spring WIZ

```bash
cd /root/workspace/wiz-java
./wiz-spring/mvnw -f wiz-spring/pom.xml test

tmp=/tmp/wiz-spring-sample
rm -rf "$tmp"
java -jar wiz-spring/target/wiz-spring-*.jar create "$tmp" --package com.example.demo
java -jar wiz-spring/target/wiz-spring-*.jar build --root "$tmp" --clean
java -jar wiz-spring/target/wiz-spring-*.jar run --root "$tmp" --port 3000
```

## Configuration Profiles

- `config/application.yml`은 모든 실행의 공통 설정입니다.
- `config/application-dev.yml`은 기본 `wiz-spring run`에서 공통 설정 다음에 읽습니다.
- `config/application-prod.yml`은 인자 없이 실행하는 standalone app jar에서 공통 설정 다음에 읽습니다.
- 다른 profile은 `wiz-spring run --profile <name>`과 `config/application-<name>.yml`로 선택합니다.

Session cookie는 공통으로 cookie-only, HttpOnly, SameSite=Lax를 사용합니다. dev는 로컬 HTTP를 위해 `Secure=false`, prod는 HTTPS 전용으로 `Secure=true`입니다. cookie timeout/name/path/domain을 바꿀 때는 `server.servlet.session.*`을 사용하세요. WIZ Spring은 server-side Servlet session을 사용하므로 cookie에는 session data가 아닌 `JSESSIONID`만 들어가며 별도 cookie 서명 secret은 필요하지 않습니다.

실제 `application*.yml`은 환경별 값과 비밀 값 보호를 위해 Git에서 제외됩니다. 생성된 `application*.example.yml`만 커밋하고, clone 후 필요한 example을 실제 파일명으로 복사하세요. `build`, `bundle`, `jar`는 실제 설정을 산출물에 포함하므로 standalone jar를 배포하기 전에도 비밀 값 포함 여부를 확인해야 합니다.

Main API smoke:

```bash
curl -i -c /tmp/wiz-cookie.txt -X POST http://127.0.0.1:3000/wiz/api/page.access/login \
  -d 'email=admin@example.com&password=admin1234'
curl -i -b /tmp/wiz-cookie.txt -X POST http://127.0.0.1:3000/wiz/api/page.dashboard/overview
curl -i http://127.0.0.1:3000/wiz/api/portal.post.list/search?page=1\&dump=5
```

## Implemented APIs

- `POST /wiz/api/page.access/login`
- `POST /wiz/api/page.dashboard/overview`
- `GET|POST /wiz/api/page.members/list`
- `POST /wiz/api/page.members/invite`
- `GET|POST /wiz/api/page.members/detail`
- `POST /wiz/api/page.members/remove`
- `GET|POST /wiz/api/page.mypage/get`
- `POST /wiz/api/page.mypage/update_profile`
- `POST /wiz/api/page.mypage/change_password`
- `GET|POST /wiz/api/portal.post.list/categories`
- `GET|POST /wiz/api/portal.post.list/search`
- `GET|POST /wiz/api/portal.post.detail/get`
- `POST /wiz/api/portal.post.detail/save`
- `POST /wiz/api/portal.post.detail/delete`

The `page.dashboard`, `page.members`, and `page.mypage` apps use the built-in `user` controller guard and require a session `id`. The post portal apps keep the original `base` controller metadata and populate author information from the current session when it is present.

## Implemented Socket

- frontend usage: `wiz.socket()`
- native WebSocket endpoint: `/wiz/app/page.chat`
- Socket.IO polling compatibility namespace: `/wiz/app/page.chat`
- events: `connect`, `join`, `send`, `disconnect`
- unauthenticated guests are labeled by socket session id, for example `Guest-1a2b3c`, so different browsers are distinguishable before login.

API/socket prefixes are baked into the Angular bundle during `wiz-spring build`.
Rebuild after changing `wiz.api.prefix` or `wiz.socket.path`.
