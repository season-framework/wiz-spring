# WizRequest

`WizRequest`는 HTTP request facade다.

```java
String method = wiz.request().method();
String path = wiz.request().path();
String lang = wiz.request().language();
String body = wiz.request().body();

String text = wiz.request().query("text", "");
String id = wiz.request().queryRequired("id");
Map<String, String> values = wiz.request().query();
Map<String, Object> json = wiz.request().json();
Optional<Object> raw = wiz.request().json("filters");
Optional<String> header = wiz.request().header("X-Request-Id");
Optional<String> cookie = wiz.request().cookie("JSESSIONID");
Optional<WizSegment> segment = wiz.request().match("/posts/{id}");
```

`query()` 병합 순서는 form body, JSON body, query string이며 query string 값이 최종 우선권을 가진다.
