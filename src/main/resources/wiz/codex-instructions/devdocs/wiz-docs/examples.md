# Examples

## List API

```java
import java.util.Map;

import com.example.demo.application.model.Struct;
import com.wiz.runtime.WizContext;

public final class PageMembersApi {
    public Object list(WizContext wiz) {
        String text = wiz.request().query("text", "");
        String role = wiz.request().query("role", "");
        Struct struct = wiz.models().get("struct", Struct.class);
        return Map.of("items", struct.user().list(text, role));
    }
}
```

## Validation

```java
public WizResult detail(WizContext wiz) {
    String id = wiz.request().queryRequired("id");
    Object item = service.get(id);
    if (item == null) {
        return wiz.response().status(404, Map.of("error", "not found"));
    }
    return wiz.response().ok(item);
}
```

## Download

```java
public WizResult export(WizContext wiz) {
    Path file = wiz.project().root().resolve("exports/report.xlsx").normalize();
    return wiz.response().download(file, "report.xlsx");
}
```
