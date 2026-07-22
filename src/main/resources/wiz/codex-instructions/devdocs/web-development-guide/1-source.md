# Source Guide


WIZ Spring은 기존 WIZ 프로젝트 구조를 유지하되 서버 런타임을 Java 21, Spring Boot 4.0.6, Maven 기반 실행 jar로 제공한다.
프로젝트 백엔드는 `api.java`, `route.java`, `socket.java`, `src/controller/*.java`, `src/model/**/*.java`가 source of truth이고,
`build/`와 `bundle/`은 `wiz-spring build`가 매번 재생성하는 산출물이다.

기본 개발 흐름:

```bash
cd /root/workspace/wiz-java/wiz-spring
./mvnw clean package

jar=/root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.2.7.jar
workspace=/tmp/wiz-spring-demo

rm -rf "$workspace"
java -jar "$jar" create "$workspace" --package com.example.demo
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


## App source

```text
src/app/page.example/
  app.json
  view.pug
  view.ts
  view.scss
  api.java
  socket.java
```

## App API


```java
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;

public final class PageExampleApi {
    public record StatusResponse(String text) {}

    public WizResult status(WizContext wiz) {
        String text = wiz.request().query("text", "hello");
        return wiz.response().status(200, new StatusResponse(text));
    }
}
```

구조가 고정된 응답은 record/class DTO를 우선 사용한다. key가 동적으로 바뀌는 자유 형식 payload에는 `Map`을 사용한다.


## Controller


```java
import com.wiz.dispatch.ControllerHook;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;

public final class UserController implements ControllerHook {
    @Override
    public WizResult before(WizContext wiz) {
        wiz.response().data("session", wiz.session().toMap());
        return wiz.auth().requireUser(wiz);
    }
}
```


## Model/Struct


```java
import com.wiz.runtime.WizContext;

public final class Struct {
    private final UserStruct user;

    public Struct(WizContext wiz) {
        this.user = new UserStruct(wiz);
    }

    public static void warmup(WizContext wiz) {
        new Struct(wiz);
    }

    public UserStruct user() {
        return user;
    }
}
```


## Route


```java
import java.util.Map;

import com.wiz.dispatch.RouteHandler;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;
import com.wiz.runtime.WizSegment;

public final class CustomEchoRouteHandler implements RouteHandler {
    @Override
    public String routeId() {
        return "custom.echo";
    }

    @Override
    public WizResult handle(WizContext wiz, WizSegment segment) {
        return wiz.response().status(200, Map.of("path", wiz.request().path()));
    }
}
```


## Socket


```java
import java.util.Map;

import com.wiz.socket.SocketController;
import com.wiz.socket.SocketEventHandler;
import com.wiz.socket.SocketEventResult;
import com.wiz.socket.SocketRoomRegistry;
import com.wiz.socket.SocketSession;

public final class PageChatSocketController implements SocketController {
    public String appId() {
        return "page.chat";
    }

    public Map<String, SocketEventHandler> handlers() {
        return Map.of("send", this::send);
    }

    private SocketEventResult send(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {
        return new SocketEventResult(true, "chat.message", payload.getOrDefault("text", "").toString());
    }
}
```
