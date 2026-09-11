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
| `backend:build` | 독립적으로 감시되는 프론트엔드 산출물을 지우지 않고 백엔드를 package합니다. |
| `build` | 백엔드와 프론트엔드를 clean build합니다. |
| `dev` | Spring과 Spring 포트에서 제공할 프론트엔드를 최초 빌드한 뒤 함께 감시합니다. |
| `bundle` | 빌드한 뒤 배포 번들을 원자적으로 게시합니다. |

프로젝트에 포함된 Maven Wrapper로 백엔드를 직접 빌드할 수도 있습니다.

```bash
./mvnw clean package
```

Angular WIZ는 `npm run wizbuild`, `npm run wizwatch`도 제공합니다. Compiler는 생성된
프로젝트의 `scripts/wizbuild.mjs`, `scripts/wizwatch.mjs`, `scripts/wiz/`에 source로
포함되며 외부 WIZ frontend package를 사용하지 않습니다.

일반적인 소스 수정 중에는 `npm run dev`를 계속 실행하십시오. 백엔드 watcher가 Java와
resource를 컴파일한 뒤 성공했을 때만 전용 DevTools trigger를 갱신합니다. 따라서 컴파일
실패 시 마지막 정상 애플리케이션은 계속 실행되고, 성공한 빌드는 같은 명령 lifecycle
안에서 한 번 재기동됩니다. 모든 프론트엔드 watcher는 성공한 결과를
`target/generated-resources/frontend`에 기록하므로 Spring URL을 새로 고치면 변경된
화면이 표시됩니다. `npm run backend:build`는 이 산출물을 보존하고, 통합
`npm run build`는 clean 백엔드 빌드와 프론트엔드 재빌드가 모두 성공한 뒤에만 실행 중
애플리케이션에 신호를 보냅니다. 표준 Angular와 React는 별도 HMR 서버가 필요할 때만
`npm run frontend:serve`를 사용합니다. 의존성 또는 빌드 설정 변경은 process classpath나
watcher 자체를 바꾸므로 전체 빌드 후 개발 process를 다시 시작해야 합니다.

## 환경 설정

모든 생성 프로젝트에는 실행 가능한 기본값이 든 `<project>/.env`가 처음부터 포함됩니다.
예제 파일을 복사하지 않고 이 파일을 직접 편집합니다. 모든 npm script가 자동으로 읽으며,
실행 전에 이미 설정한 process 환경 변수가 우선합니다. 기본 개발 서비스도 systemd를 통해
같은 파일을 읽습니다. 저장소에 포함되는 기본값에는 secret을 넣지 말고 credential은 process
환경이나 외부 service `--env-file`로 주입합니다.

자주 쓰는 항목은 `SERVER_PORT`, `APP_API_PREFIX`, `SPRING_PROFILES_ACTIVE`, 그리고 예제에
나열된 `APP_DATASOURCE_*`입니다. 서비스의 `--port`, `--profiles` 옵션은 같은 이름의 환경
설정보다 우선합니다. 다른 위치를 쓰려면 `--env-file /absolute/path/service.env`를
사용합니다. 상대 경로는 개발 시 프로젝트 root, production 시 bundle root 기준입니다.

소스 수정은 실시간 감시 대상입니다. 환경 변수는 process 시작 시 읽는 설정이므로 설치된
서비스가 사용하는 `.env`를 바꾼 뒤에는 `systemctl restart wiz.<name>`을 실행합니다.

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
├── application.jar         # JSP는 application.war
├── public/
├── .env
└── docker-compose.yaml
```

Bundle 디렉터리에서 archive를 직접 실행합니다.

```bash
cd bundle
set -a
. ./.env
set +a
java -jar "$APP_ARTIFACT"
```

Spring Boot가 executable JAR에서 JSP를 지원하지 않으므로 JSP는 executable WAR를
사용합니다. 다른 템플릿은 executable JAR와 독립 프론트엔드 tree를 생성합니다.
복사한 `bundle/`은 JDK 25 이상만 있으면 직접 실행할 수 있고, 위처럼 `.env`를 읽을
때는 POSIX shell도 사용합니다. Node.js, npm, Maven, WIZ Spring 설치는 필요하지 않습니다.
배포 환경에서 값을 바꿔야 하면 `.env`를 읽은 뒤 해당 환경 변수를 다시 export합니다.
네 항목을 복사할 때는 transport 또는 artifact repository의 무결성 기능을 사용합니다.
WIZ Spring은 별도의 manifest/checksum 계층을 더 이상 만들지 않습니다.

## Docker Compose

필요하면 `bundle/.env`를 직접 편집한 뒤 빌드된 애플리케이션을 시작합니다.

```bash
cd bundle
docker compose up -d
```

Compose에는 JRE image를 사용하는 Spring service 하나만 있습니다. Archive와 `public/`을
read-only로 bind mount하고 `SERVER_PORT`를 publish하며 application data는 named volume에
보관합니다. Image를 build하거나 reverse proxy를 시작하지 않습니다.

Host에서 관리할 설정 예시는 생성 source 프로젝트의
`deploy/nginx/default.conf.example`과 `deploy/apache2/wiz.conf.example`에 남아 있습니다.
애플리케이션은 `127.0.0.1:8080`, frontend는 `/srv/wiz/public`에 설치됐다고 가정하므로
설치 전에 환경에 맞게 바꾸십시오. 예시는 SSE buffering 비활성화와 WebSocket forwarding을
포함합니다. TLS, 인증서, secret, 운영 data storage는 배포 환경에서 구성해야 합니다.

## systemd 서비스

기본 서비스 모드는 실시간 개발 모드입니다. 생성 프로젝트의 `npm run dev`를 실행하므로
소스 변경을 서비스 재설치나 수동 재시작 없이 빌드하고 적용합니다.

```bash
wiz-spring service install dashboard \
  --root /srv/dashboard \
  --user dashboard
```

변경되지 않는 배포 산출물이 필요할 때만 production 모드를 사용합니다. 먼저
`npm run bundle`을 실행한 뒤 다음과 같이 설치합니다.

```bash
wiz-spring service install dashboard \
  --production \
  --root /srv/dashboard \
  --user dashboard
```

`--production`은 `<root>/bundle`을 사용합니다. `--bundle /another/path`는 production
모드를 선택하면서 bundle 위치도 바꿉니다. Production unit은 bundle artifact를 직접
실행합니다. Installer는 launcher에 절대 경로의 `npm` 또는 `java` 명령을 기록하며,
설치된 두 모드는 WIZ Spring executable이나 generator JAR를 호출하거나 요구하지
않습니다. 개발 모드의 기본 Spring profile은 `dev`, production은 `prod`입니다.
Installer는 root의 `application.jar` 또는 `application.war`를 자동 선택하며 WIZ Spring
1.2.1이 만든 manifest/checksum bundle도 계속 검증해 설치합니다. 두 모드 모두 journald에
기록하고 기본 `.env`를 읽으며 설치 직후와 재부팅 시 자동
기동합니다. `--port`, `--profiles`, 명시적으로 설정한 unit 환경이 기본값보다 우선하고,
다른 설정 파일은 `--env-file`로 선택합니다.

Root 소유 프로젝트나 번들은 root가 아닌 `--user` 또는 명시적인 `--allow-root` 확인이 필요합니다.
관리 명령으로 `list`, `status`, `logs`, `start`, `stop`, `restart`, `uninstall`을
제공합니다. 전체 옵션은 `wiz-spring service <command> --help`에서 확인하십시오.

## 관련 문서

- [프로젝트 생성과 import](project-generation.ko.md)
- [생성되는 배포 안내서](../src/main/resources/wiz/templates/project-common/deploy/README.md)
- [AI 빌드·배포 계약](../src/main/resources/wiz/templates/project-common/docs/ai/deployment.md)
