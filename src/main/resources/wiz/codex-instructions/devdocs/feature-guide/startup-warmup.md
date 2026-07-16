# Startup Warmup

WIZ Spring runtime은 서버 시작 시 app의 선택적 `Struct.warmup(WizContext)` hook을 호출할 수 있다. 기본값은 `wiz.runtime.warmup-enabled: true`다.

Warmup은 첫 사용자 요청이 지불하던 초기화 비용을 서버 기동 단계로 옮기는 장치다. DB에 한정하지 않고, app이 실제 요청 전에 준비해야 하는 비용 큰 resource를 명시적으로 초기화한다.

## Hook

`src/model/Struct.java`에 public static method를 둔다.

```java
import com.wiz.runtime.WizContext;

public final class Struct {
    private final UserStruct user;

    public Struct(WizContext wiz) {
        this.user = new UserStruct(wiz);
    }

    public static void warmup(WizContext wiz) {
        new Struct(wiz);
    }

    public UserStruct user() {
        return user;
    }
}
```

`warmup`은 idempotent해야 한다. 여러 번 실행되어도 데이터가 중복 생성되거나 외부 side effect가 반복되면 안 된다.

## Warmup 대상

권장 대상:

- DB connection pool, JPA/Hibernate metadata, schema validate/update
- idempotent seed data, migration marker 확인, 필수 config 검증
- 외부 SDK client 생성, token/public key/JWKS cache, template/cache metadata preload
- 첫 요청에서 매번 필요하고 runtime cache와 생명주기가 맞는 resource

Warmup에서 생성한 장기 resource는 `wiz.projectRuntime().onClose(...)`에 close hook을 등록한다. health/readiness나 metrics가 필요한 resource는 `wiz.observability()`를 통해 core actuator health와 Micrometer meter에 연결한다.

피해야 할 대상:

- 로그인 사용자별 session/context가 필요한 작업
- 오래 걸리는 batch, 전체 데이터 scan, report 생성
- 결제/발송/쓰기 API처럼 non-idempotent 외부 호출
- 모든 portal/app/API를 무조건 실행하는 방식
- 실패하면 서버를 반드시 내려야 하는 검증. 이런 검증은 별도 health/readiness나 deploy check로 분리한다.

## 전부 Warmup하지 않는 이유

무조건 전부 실행하면 첫 요청 지연은 줄 수 있지만, 서버 기동 시간이 길어지고 외부 장애가 runtime 시작을 불안정하게 만든다.

기본 정책은 현재 workspace app의 명시적 hook만 실행하는 것이다. 무엇을 준비할지는 `Struct.warmup`이 결정하고, 실패는 warning으로 남긴 뒤 서버 기동은 계속한다. 운영에서 DB 준비 실패를 배포 실패로 다뤄야 하면 readiness check나 별도 smoke test에서 강하게 검증한다.

## 설정

```yaml
wiz:
  runtime:
    warmup-enabled: true
```

외부 DB가 늦게 뜨거나 개발 중 서버 기동을 빠르게 유지해야 하는 환경에서는 `false`로 끌 수 있다.
