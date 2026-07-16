# API Reference

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


세부 facade 문서는 `api/` 하위 문서를 확인한다.
