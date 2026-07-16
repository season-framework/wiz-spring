# Development Workflow

## 1. 구조 확인

```bash
find src -maxdepth 3 -type f | sort
```

App, route, controller, model 중 어느 계층을 바꿀지 먼저 정한다.

## 2. 코드 작성

- 화면 API는 `src/app/{app_id}/api.java`
- 공통 domain은 `src/model/**/*.java`
- portal domain은 `src/portal/{pkg}/model/**/*.java`
- 인증/권한은 `src/controller/*Controller.java` 또는 `src/model/AuthService.java`
- 독립 route는 `src/route/{route_id}/route.java`

## 3. 빌드

```bash
java -jar "$jar" build --root "$workspace" --clean
```

빌드는 source 재구성, Java compile, workspace dependency 복사, Angular build 또는 fallback bundle 생성을 수행한다.
WIZ runtime은 source/config 변경을 watch하지 않으므로 `src/`, `config/`, `pom.xml`, `src/angular/package.json` 수정 후에는 이 빌드를 실행해 `bundle/.wiz-build.json` marker를 갱신해야 한다.

## 4. 실행 확인

```bash
java -jar "$jar" run --root "$workspace" --port 3000
curl -i http://127.0.0.1:3000/actuator/health
```

## 5. 회귀 확인

```bash
cd /root/workspace/wiz-java
bash scripts/contract-spring-http.sh
bash scripts/e2e-spring-smoke.sh
```

문서만 수정한 경우에는 최소한 링크/용어 검색으로 legacy backend 전제가 남지 않았는지 확인한다.
