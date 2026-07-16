# Service API

App service extension은 convention 또는 설정으로 로드한다.

## Convention

```text
src/model/AuthService.java
src/model/SessionService.java
```

## Configuration

```yaml
wiz:
  auth:
    service-class: com.example.demo.CustomAuthService
  session:
    service-class: com.example.demo.CustomSessionService
```

`AuthService`는 `check`, `logout`, `requireUser`, `requireAdmin`, `oidcPlaceholder`, `samlPlaceholder`를 override할 수 있다.
`SessionService`는 `has`, `get`, `set`, `delete`, `clear`, `invalidate`, `toMap`, `userId`를 제공한다.
