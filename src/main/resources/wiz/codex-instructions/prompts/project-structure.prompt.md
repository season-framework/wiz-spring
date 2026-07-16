# Project Structure Prompt

WIZ Spring workspace를 다룰 때 다음 구조를 기준으로 답한다.

```text
workspace/
  config/application.yml
  config/application-dev.yml
  config/application-prod.yml
  config/application.example.yml
  config/application-dev.example.yml
  config/application-prod.example.yml
  pom.xml
  src/angular/
  src/app/{app_id}/app.json
  src/app/{app_id}/view.pug
  src/app/{app_id}/view.ts
  src/app/{app_id}/view.scss
  src/app/{app_id}/api.java
  src/app/{app_id}/socket.java
  src/controller/*Controller.java
  src/model/**/*.java
  src/route/{route_id}/app.json
  src/route/{route_id}/route.java
  src/portal/{package}/...
  build/
  bundle/
  target/
```

생성 산출물인 `build/`, `bundle/`, `target/`, `node_modules/`를 source로 설명하지 않는다.

실제 `application.yml`, `application-<profile>.yml`은 Git에서 제외하고 공유 가능한 값은 `application*.example.yml`에 반영한다. `application.yml`은 공통, 기본 `wiz-spring run`은 `dev`, 인자 없는 standalone jar는 `prod` profile을 추가로 읽는다고 설명한다.
