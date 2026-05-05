# WIZ Java Sample Project

Spring WIZ runtime에서 실행되는 Java backend 샘플 프로젝트입니다. 기존 sample의 frontend/App 구조는 유지하고, backend는 app-local `api.java`, Java controller, Java Struct/domain source로 구성했습니다.

## Demo Accounts

| Email | Password | Name | Role |
| --- | --- | --- | --- |
| admin@example.com | admin1234 | 관리자 | admin |
| alice@example.com | alice1234 | Alice Kim | user |
| bob@example.com | bob12345 | Bob Park | user |
| carol@example.com | carol123 | Carol Lee | editor |
| dave@example.com | dave1234 | Dave Choi | viewer |

The Java Struct layer seeds these accounts and starter posts idempotently on first API access. The login page also shows the admin sample account so a freshly generated project can be tested immediately.

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

Database access is project-local. This sample uses Spring ORM with JPA/Hibernate and SQLite through the project `pom.xml`; the runtime core does not contain DB/ORM implementation code. Common DB setup lives under `src/portal/season/model/orm`, and entity-specific repository helpers are nested inside each entity class. Change `config/application.yml` key `sample.datasource.url` or replace the project entity/helper classes when using another database.

## Run With Spring WIZ

```bash
cd /root/workspace/wiz-java
./wiz-spring/mvnw -f wiz-spring/pom.xml test

tmp=/tmp/wiz-java-sample
rm -rf "$tmp"
java -jar wiz-spring/target/wiz-spring-*.jar create "$tmp"
java -jar wiz-spring/target/wiz-spring-*.jar project create --root "$tmp" --project main
java -jar wiz-spring/target/wiz-spring-*.jar project build --root "$tmp" --project main --clean
java -jar wiz-spring/target/wiz-spring-*.jar run --root "$tmp" --port 3000
```

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
- HTTP(S) namespace: `/wiz/app/main/page.chat`
- events: `connect`, `join`, `send`, `disconnect`
- unauthenticated guests are labeled by socket session id, for example `Guest-1a2b3c`, so different browsers are distinguishable before login.
