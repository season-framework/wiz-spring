# WizSession

`wiz.session()`은 Servlet session을 감싼다.

```java
wiz.session().set("id", userId);
wiz.session().set(Map.of("role", "admin", "name", "Admin"));
boolean hasId = wiz.session().has("id");
Object role = wiz.session().get("role", "").toString();
Map<String, Object> data = wiz.session().toMap();
wiz.session().delete("temporary");
wiz.session().clear();
wiz.session().invalidate();
```

Project-local 확장이 필요하면 `src/model/SessionService.java`에서 `com.wiz.session.SessionService`를 상속한다.
