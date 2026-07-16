# Session And Auth Guide

## 기준

WIZ Spring의 기본 인증은 Servlet session과 app-local `AuthService`/`SessionService` 확장으로 구성한다. core는 기본 guard와 `/auth/check`, `/auth/logout`, OIDC/SAML placeholder를 제공하고, 실제 계정/권한/세션 저장 정책은 app source에서 구현한다.

Flask의 기본 session은 client cookie에 session payload를 서명해 저장하지만, WIZ Spring의 기본 Servlet `HttpSession`은 session data를 server에 저장한다. browser의 `JSESSIONID` cookie는 payload나 암호화된 사용자 정보가 아니라 임의의 lookup ID다. 따라서 cookie 서명용 `wiz.secret`은 필요하지 않으며 WIZ Spring도 해당 설정을 생성하거나 읽지 않는다.

기본 session 저장소는 단일 process memory다. 서버 재시작 뒤에도 session을 유지하거나 여러 instance가 같은 session을 사용해야 하면 Spring Session과 Redis/JDBC 같은 공용 저장소를 별도로 구성한다. 단순 load balancer sticky session은 instance 장애나 재시작에 대한 영속성을 제공하지 않는다.

## 기본 흐름

1. 로그인 App API가 사용자 정보를 검증한다.
2. 성공 시 `wiz.session().set(...)`으로 `id`, `email`, `name`, `role` 같은 값을 저장한다.
3. 보호된 App은 `app.json.controller`를 `user` 또는 `admin`으로 둔다.
4. Controller chain이 `wiz.auth().requireUser(wiz)` 또는 `requireAdmin(wiz)`를 실행한다.
5. `/auth/check`는 HTTP 200과 `{status, session}` data를 반환한다.
6. `/auth/logout`은 session을 invalidate하고 configured cookie를 만료시킨 뒤 redirect한다.

## Login API

```java
import java.util.Locale;
import java.util.Map;

import com.example.demo.application.model.Struct;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;

public final class PageAccessApi {
    public WizResult login(WizContext wiz) {
        String email = wiz.request().query("email", "").trim().toLowerCase(Locale.ROOT);
        String password = wiz.request().query("password", "");
        Struct struct = wiz.models().get("struct", Struct.class);
        Map<String, Object> user = struct.user().authenticate(email, password);
        if (user == null) {
            return wiz.response().status(401, Map.of("message", "invalid credentials"));
        }
        wiz.session().set(Map.of(
                "id", user.get("id"),
                "email", user.get("email"),
                "name", user.get("name"),
                "role", user.get("role")));
        return wiz.response().ok(user);
    }
}
```

## Custom AuthService

`src/model/AuthService.java`를 두면 convention으로 자동 로드된다. 다른 위치를 쓰려면 workspace `config/application.yml`에 `wiz.auth.service-class`를 지정한다.

```java
import java.util.Map;

import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;

public class AuthService extends com.wiz.session.AuthService {
    @Override
    public WizResult requireUser(WizContext context) {
        if (context.session().userId().isPresent()) {
            return null;
        }
        return context.response().status(401, Map.of("error", "unauthorized"));
    }
}
```

## DB-backed remote logout

원격 로그아웃이 필요하면 session table을 app model에 만들고 `session_token`을 Servlet session과 DB 양쪽에 저장한다. Controller guard에서 token active 상태를 조회해 false면 session을 invalidate하고 401을 반환한다.

권장 구성:

```text
src/model/
  AuthService.java
  SessionService.java
  Struct.java
  db/UserEntity.java
  db/UserSessionEntity.java
  struct/UserStruct.java
  struct/UserSessionStruct.java
```

`UserSessionStruct`는 `register`, `active`, `touch`, `revoke`, `revokeByUser` 같은 method를 제공한다. DB implementation은 workspace `pom.xml` 의존성과 app config를 통해 결정한다.

## Config

```yaml
wiz:
  auth:
    service-class: com.example.demo.application.model.AuthService
  session:
    service-class: com.example.demo.application.model.SessionService

server:
  servlet:
    session:
      timeout: 30m
      tracking-modes:
        - cookie
      cookie:
        name: JSESSIONID
        path: /
        http-only: true
        same-site: lax
        secure: true
```

Session cookie 설정은 전부 `application*.yml`의 `server.servlet.session.*`에서 관리한다. 별도 `season.yml` 설정은 사용하지 않는다. core logout은 현재 Servlet container의 실제 cookie name/domain/path/HttpOnly/Secure/SameSite/Partitioned 설정을 읽어 같은 cookie를 만료시킨다.

기본 생성값은 dev에서 `Secure=false`, prod에서 `Secure=true`다. prod는 HTTPS로 서비스하고, cross-site 구성이 필요할 때만 `SameSite=None`과 `Secure=true`를 함께 사용한다.

## Checklist

- `page.access/api.java`에서 인증 성공 시 session key를 저장한다.
- 보호 App의 `app.json.controller`를 `user` 또는 `admin`으로 지정한다.
- custom guard가 필요하면 `AuthService`를 override한다.
- 원격 revoke가 필요하면 DB session token을 추가하고 guard에서 매 요청 검증한다.
- `/auth/check`, `/auth/logout`을 curl로 확인한다.
