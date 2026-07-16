# API Reference Prompt

## Request

```java
String value = wiz.request().query("key", "default");
String id = wiz.request().queryRequired("id");
Map<String, String> query = wiz.request().query();
Map<String, Object> json = wiz.request().json();
Optional<WizSegment> segment = wiz.request().match("/items/{id}");
```

## Response

```java
return wiz.response().ok(data);
return wiz.response().status(400, Map.of("error", "invalid"));
return wiz.response().redirect("/");
return wiz.response().download(path, "file.zip");
```

## Session/Auth

```java
wiz.session().set("id", userId);
wiz.session().set(Map.of("role", "admin"));
return wiz.auth().requireUser(wiz);
```

## Model

```java
Struct struct = wiz.models().get("struct", Struct.class);
```
