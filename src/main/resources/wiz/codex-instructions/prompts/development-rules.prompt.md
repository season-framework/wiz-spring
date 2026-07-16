# Development Rules Prompt

WIZ Spring 코드를 제안할 때:

- backend 예제는 Java 21과 `WizContext` 기준으로 작성한다.
- App API는 `api.java`, Route는 `route.java`, Socket은 `socket.java`를 사용한다.
- response는 `return wiz.response().status(...)` 형태로 반환한다.
- domain 로직은 `src/model` 또는 `src/portal/{package}/model`에 둔다.
- Java 의존성은 workspace `pom.xml`, frontend 의존성은 `src/angular/package.json`에 둔다.
- auth guard는 `ControllerHook`과 `AuthService`로 표현한다.
- startup warmup은 `Struct.warmup(WizContext)`에 명시하고 idempotent하게 작성한다.
- warmup은 DB pool/JPA metadata, seed, SDK/cache preload처럼 첫 요청 전에 공통으로 필요한 작업만 수행한다.
- warmup을 모든 API/portal 실행, 사용자/session 의존 작업, 긴 batch, non-idempotent 외부 호출로 확대하지 않는다.
- 생성 산출물 수정을 해결책으로 제안하지 않는다.
- 실제 `application*.yml`의 Git ignore를 유지하고, `application*.example.yml`에 secret이나 credential을 넣지 않는다.
- profile 설정을 제안할 때 공통값은 `application.yml`, 환경별 override는 `application-<profile>.yml`에 둔다.
