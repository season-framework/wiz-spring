# WizResponse

`WizResponse`는 `WizResult`를 만드는 builder다.

```java
return wiz.response().ok(data);
return wiz.response().status(201, data);
return wiz.response().status(400, Map.of("error", "invalid"));
return wiz.response().redirect("/");
return wiz.response().download(path, "file.zip");
return wiz.response().header("X-Feature", "enabled").status(200, data);
return wiz.response().cookie("name", "value").ok(data);
return wiz.response().deleteCookie("name").ok(data);
```

Controller에서 `wiz.response().data("session", value)`를 호출하면 후속 envelope에 공통 data를 추가할 수 있다.
