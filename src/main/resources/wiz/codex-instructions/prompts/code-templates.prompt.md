# Code Templates Prompt

## App API


```java
import java.util.Map;

import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;

public final class PageExampleApi {
    public WizResult status(WizContext wiz) {
        String text = wiz.request().query("text", "hello");
        return wiz.response().status(200, Map.of("text", text));
    }
}
```


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


## Struct


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
