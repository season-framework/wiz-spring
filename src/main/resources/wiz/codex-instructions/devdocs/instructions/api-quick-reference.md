# API Quick Reference

## WizContext

| Method | 용도 |
| --- | --- |
| `wiz.request()` | HTTP request facade |
| `wiz.response()` | response/result builder |
| `wiz.session()` | Servlet session wrapper |
| `wiz.auth()` | app 또는 core auth service |
| `wiz.config()` | workspace config namespace loader |
| `wiz.models()` | app model loader |
| `wiz.project()` | 현재 workspace app context |

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

구조가 고정된 응답은 record/class DTO를 우선 사용한다. `Map`은 key가 실행 시점에 달라지는 자유 형식 payload에만 사용한다.


## Request

```java
String text = wiz.request().query("text", "hello");
String required = wiz.request().queryRequired("id");
Map<String, String> values = wiz.request().query();
Map<String, Object> json = wiz.request().json();
String method = wiz.request().method();
String path = wiz.request().path();
```

## Response

```java
return wiz.response().ok(new StatusResponse("ok"));
return wiz.response().status(400, Map.of("error", "bad request"));
return wiz.response().redirect("/");
return wiz.response().download(path, "report.xlsx");
return wiz.response().header("X-App", "main").status(200, data);
```

## Session/Auth

```java
wiz.session().set("id", userId);
wiz.session().set(Map.of("role", "admin", "name", "Alice"));
Object role = wiz.session().get("role", "").toString();
boolean loggedIn = wiz.session().userId().isPresent();
return wiz.auth().requireUser(wiz);
```

## Model

```java
Struct struct = wiz.models().get("struct", Struct.class);
Map<String, Object> user = struct.user().get(id);
```
