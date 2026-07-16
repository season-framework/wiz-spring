# Examples Prompt

## 로그인 API 형태

```java
public WizResult login(WizContext wiz) {
    String email = wiz.request().query("email", "").trim();
    String password = wiz.request().query("password", "");
    Map<String, Object> user = service.authenticate(email, password);
    if (user == null) {
        return wiz.response().status(401, Map.of("message", "invalid credentials"));
    }
    wiz.session().set(Map.of("id", user.get("id"), "role", user.get("role")));
    return wiz.response().ok(user);
}
```

## 목록 API 형태

```java
public Object list(WizContext wiz) {
    String text = wiz.request().query("text", "");
    int page = Integer.parseInt(wiz.request().query("page", "1"));
    return service.search(text, page);
}
```
