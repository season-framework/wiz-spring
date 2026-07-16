# Response Return Pattern

Spring port에서는 response를 예외처럼 던지는 패턴을 사용하지 않는다.

올바른 형태:

```java
if (invalid) {
    return wiz.response().status(400, Map.of("error", "invalid"));
}
return wiz.response().ok(data);
```

`WizResult`를 만든 뒤 반환하지 않으면 dispatcher가 의도한 status/header를 알 수 없다.
