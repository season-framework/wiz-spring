[English](build-and-deployment.md) | [한국어](build-and-deployment.ko.md)

# 빌드와 배포

생성된 프로젝트가 자체 빌드 lifecycle을 소유합니다. WIZ Spring generator JAR는 빌드
plugin이 아니며 애플리케이션 런타임에도 필요하지 않습니다.

## 프로젝트 명령

새 프로젝트에서는 lockfile 의존성을 설치한 뒤 모든 템플릿에서 같은 공통 script를
사용합니다.

```bash
npm ci
npm run frontend:build
npm run backend:build
npm run build
npm run dev
npm run bundle
```

| Script | 결과 |
| --- | --- |
| `frontend:build` | 선택한 프론트엔드만 빌드합니다. |
| `backend:build` | Maven clean/package workflow를 실행합니다. |
| `build` | 백엔드와 프론트엔드를 clean build합니다. |
| `dev` | Spring, 백엔드 compile watcher, 프론트엔드 watcher를 함께 실행합니다. |
| `bundle` | 빌드한 뒤 배포 번들을 원자적으로 게시합니다. |

프로젝트에 포함된 Maven Wrapper로 백엔드를 직접 빌드할 수도 있습니다.

```bash
./mvnw clean package
```

Angular WIZ는 `npm run wizbuild`, `npm run wizwatch`도 제공합니다. Compiler는 생성된
프로젝트의 `scripts/wizbuild.mjs`, `scripts/wizwatch.mjs`, `scripts/wiz/`에 source로
포함되며 외부 WIZ frontend package를 사용하지 않습니다.

## API prefix와 path version

Business controller에는 resource path만 선언합니다.

```java
@ApiController("/dashboard")
public class DashboardController {
    @GetMapping
    public String dashboard() {
        return "ready";
    }
}
```

생성된 Spring MVC 설정이 전역 prefix를 중앙에서 적용합니다.

```yaml
app:
  api:
    prefix: ${APP_API_PREFIX:/api}
```

위 예제는 `/api/dashboard`에 매핑됩니다. Controller를 수정하지 않고
`APP_API_PREFIX=/api/v2`로 prefix를 바꿀 수 있습니다. 프론트엔드는 런타임에
`/app-config.json`에서 실제 client prefix를 읽습니다.

여러 버전을 동시에 제공하려면 `APP_API_VERSIONING_MODE=path`를 설정하고
`APP_API_DEFAULT_VERSION`과 지원 버전을 구성한 뒤 controller mapping에 version을
선언합니다. Prefix는 `/api`로 유지하고 Spring path versioning이 버전 segment를
추가합니다.

## 번들 구조

`npm run bundle`은 같은 source revision의 백엔드와 프론트엔드 산출물을 게시합니다.

```text
bundle/
├── app/application.jar     # JSP는 application.war
├── public/
├── config/
├── deploy/
│   ├── nginx/
│   ├── apache2/
│   └── docker/
├── docker-compose.yaml
├── manifest.json
└── SHA256SUMS
```

배포 전에 번들을 검증합니다.

```bash
cd bundle
sha256sum -c SHA256SUMS
```

Spring Boot가 executable JAR에서 JSP를 지원하지 않으므로 JSP는 executable WAR를
사용합니다. 다른 템플릿은 executable JAR와 독립 프론트엔드 tree를 생성합니다.

## Docker Compose

`bundle/.env.example`을 `bundle/.env`로 복사한 뒤 reverse proxy profile 하나만
실행합니다.

```bash
cd bundle
docker compose --profile nginx up -d
# 또는
docker compose --profile apache2 up -d
```

Backend container는 UID/GID `10001`로 실행됩니다. 제공되는 proxy 설정은 SSE 응답을
buffering하지 않습니다. TLS, 인증서, secret, 운영 data storage는 배포 환경에서
환경 변수 또는 외부 Spring 설정으로 구성해야 합니다.

## systemd 서비스

완성된 번들을 generator CLI로 설치합니다.

```bash
wiz-spring service install dashboard \
  --bundle /srv/dashboard/bundle \
  --user dashboard
```

설치된 unit은 bundle artifact를 직접 실행하며 재부팅 후 자동 기동하도록 enable됩니다.
Generator JAR를 호출하지 않습니다. 기본 Spring profile은 `prod,bundle`이고 출력은
journald에 기록됩니다. `--profiles`로 profile 목록을 바꿀 수 있습니다.

Root 소유 번들은 root가 아닌 `--user` 또는 명시적인 `--allow-root` 확인이 필요합니다.
관리 명령으로 `list`, `status`, `logs`, `start`, `stop`, `restart`, `uninstall`을
제공합니다. 전체 옵션은 `wiz-spring service <command> --help`에서 확인하십시오.

## 관련 문서

- [프로젝트 생성과 import](project-generation.ko.md)
- [생성되는 배포 안내서](../src/main/resources/wiz/templates/project-common/deploy/README.md)
- [AI 빌드·배포 계약](../src/main/resources/wiz/templates/project-common/docs/ai/deployment.md)
