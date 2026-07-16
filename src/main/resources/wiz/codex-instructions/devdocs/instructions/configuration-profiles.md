# Configuration Profiles

## 로딩 규칙

WIZ Spring은 같은 key를 뒤의 값으로 덮어쓰며 다음 순서로 설정을 병합한다.

1. `wiz-spring` core 기본값
2. workspace `config/application.yml`
3. 선택된 profile의 `config/application-<profile>.yml`
4. `wiz-spring run`의 `--host`, `--port`, `--profile` 같은 명시적 CLI 옵션

Spring `@ConfigurationProperties`와 workspace Java 코드의 `wiz.config().namespace("application")`은 동일한 active profile 구성을 사용한다. 여러 profile이 활성화되면 나중 profile이 앞 profile의 같은 key를 덮어쓴다.

| 실행 방식 | 기본 profile | 추가로 읽는 파일 |
| --- | --- | --- |
| `wiz-spring run --root <workspace>` | `dev` | `application-dev.yml` |
| `service regist`로 만든 systemd 서비스 | `dev` | `application-dev.yml` |
| `java -jar <standalone-app>.jar`를 인자 없이 실행 | `prod` | `application-prod.yml` |
| `wiz-spring run --profile <name>` | `<name>` | `application-<name>.yml` |

모든 경우 `application.yml`을 먼저 읽는다. 선택된 profile 파일이 없으면 공통 설정만 사용한다. `application*.example.yml`은 runtime이 읽지 않는다.

## 파일별 책임

- `application.yml`: profile에 무관한 공통 runtime 값
- `application-dev.yml`: 개발 PC나 개발 서비스에만 필요한 override. 생성 시 session cookie `Secure=false`가 들어간다.
- `application-prod.yml`: standalone 운영 배포에만 필요한 override. 생성 시 session cookie `Secure=true`가 들어가며 HTTPS를 전제로 한다.
- `application-<name>.yml`: staging 등 명시적으로 선택하는 추가 환경

한 환경에서만 달라지는 DB 계정, 외부 API credential, endpoint는 공통 파일에 중복하지 않는다. frontend bundle에 편입되는 `wiz.api.prefix`, `wiz.socket.path`처럼 dev/prod가 같은 값을 써야 하는 설정은 공통 `application.yml`에 둔다.

생성 직후 `application.yml`의 활성 값은 workspace별 `server.port`, `wiz.java.package-root`와 공통 session cookie 보안 정책뿐이다. 사용처 없는 값이나 core 기본값과 같은 API/HTTP/socket/redirect/runtime 값은 중복하지 않는다.

Session cookie 공통 `application.yml` 정책은 cookie tracking only, `HttpOnly=true`, `SameSite=Lax`다. 환경별 `Secure` 값만 dev/prod profile에 둔다. timeout, name, path, domain 또는 cross-site 정책은 `server.servlet.session.*`에서 변경한다. `SameSite=None`을 쓰면 반드시 `Secure=true`와 HTTPS를 함께 사용한다.

`config/wiz.yml`은 runtime 설정이 아니라 workspace 판별용 metadata다. `workspace`, `format-version`, `runtime.name`, `runtime.version`을 기록하며 Java workspace 판별과 MCP status에 사용한다. 설정 override를 이 파일에 넣지 않는다.

## Git과 비밀 값

`wiz-spring create`는 실제 `application.yml`, `application-*.yml`을 `.gitignore`에 넣는다. 생성 후 이 파일들에 environment credential이나 로컬 endpoint가 추가될 수 있기 때문이다. 대신 생성 시점부터 비밀 값이 없는 다음 공유용 파일을 함께 만든다.

```text
config/application.example.yml
config/application-dev.example.yml
config/application-prod.example.yml
```

Git에는 example 파일만 커밋한다. 팀에 필요한 key를 추가하거나 기본값을 바꾸면 실제 파일과 해당 example을 함께 수정하되 example에는 비밀 값을 넣지 않는다.

clone 후에는 필요한 파일을 복원한다.

```bash
cp config/application.example.yml config/application.yml
cp config/application-dev.example.yml config/application-dev.yml
```

운영 환경에서는 검토된 `application-prod.example.yml`을 `application-prod.yml`로 복사하고 실제 값을 별도로 주입한다. ignore 규칙을 제거해 실제 설정을 강제로 commit하지 않는다.

## 빌드와 배포

config 수정 후에는 `wiz-spring build --root <workspace> --clean`을 실행한다. `build`, `bundle`, `jar`는 Git ignore와 무관하게 실제 `config/` 파일을 산출물에 복사한다. standalone jar에도 실제 설정이 포함되므로 배포 전에 다음을 확인한다.

- 불필요한 dev/staging credential이 포함되지 않았는가
- artifact와 checksum의 접근 권한이 적절한가
- 배포 대상에서 `prod` profile을 읽는가

## 0.2.2 workspace migration

`0.2.2`로 생성해 이미 개발 중인 workspace는 `create`를 다시 실행하거나 source를 교체하지 않는다. 다음 항목만 기존 custom 값과 병합한다.

- `application.yml`의 사용되지 않는 `wiz.secret`을 삭제하고 공통 cookie-only/HttpOnly/SameSite 정책을 추가한다.
- `season.yml`의 `session_cookie_*`를 제거하고 `server.servlet.session.*`으로 옮긴다. app code가 읽는 다른 `season.yml` key는 유지한다.
- dev에는 `Secure=false`, prod에는 `Secure=true`를 추가한다.
- `wiz.yml`을 `workspace`, `format-version`, `runtime.name`, `runtime.version` 형식으로 갱신한다.
- 실제 application config를 ignore하고 비밀 값을 제거한 example 파일을 만든다. 이미 tracked된 실제 config는 `git rm --cached`로 별도 untrack한다.
- 변경 후 새 runtime으로 clean build하고 dev HTTP 및 prod HTTPS session/logout을 확인한다.

상세 명령과 삭제 전 참조 확인 항목은 `wiz-spring/release-log/0.2.4.md`를 따른다.
