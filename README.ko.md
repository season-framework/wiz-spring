[English](README.md) | [한국어](README.ko.md)

<div align="center">

# WIZ Spring

**프로젝트에 맞는 프론트엔드 구조와 표준 Spring 백엔드를 생성합니다.**

Java 21 · Spring Boot 4 · Angular WIZ · Angular · React · HTML · JSP

</div>

`wiz-spring` 1.0.0은 프로젝트 생성기이자 선택적으로 사용할 수 있는 systemd 서비스
관리자입니다. 생성된 프로젝트는 프로젝트에 포함된 Maven과 스크립트만으로 빌드,
watch, 실행, 번들을 처리합니다. 생성기 JAR는 프로젝트를 생성하거나 서비스를
관리할 때만 필요하며, 빌드 및 애플리케이션 런타임에는 참여하지 않습니다.

`.wiz` 디렉터리나 비공개 WIZ NPM 빌드 패키지는 만들지 않습니다.

## 0.2.x와의 호환성 경계

> **중요:** 1.0.0은 0.2.8을 포함한 0.2.x 계열의 인플레이스 업그레이드가 아니라
> 완전히 분리된 새 구조입니다.

WIZ Spring 1.0.0은 0.2.8 workspace의 WIZ 백엔드 변환 구조, 런타임 설정, 빌드·번들
산출물 또는 기존 systemd unit을 자동으로 탐지·변환·마이그레이션하지 않습니다. 기존
프로젝트에는 해당 0.2.8 런타임과 도구를 계속 사용하거나, 새 1.0 프로젝트를 생성한 뒤
표준 Spring 및 선택한 프론트엔드 구조로 코드를 수동 이전해야 합니다. 1.0의
`create`/import/`service` 명령을 0.2.8 workspace나 bundle의 직접 업그레이드 수단으로
사용하지 마십시오.

## 생성기 빌드

```bash
./mvnw clean package
alias wiz-spring='java -jar /absolute/path/to/wiz-spring/target/wiz-spring-1.0.0.jar'
```

## 프로젝트 생성

기본 템플릿은 Angular WIZ입니다.

`create`는 프로젝트 파일을 쓰기 전에 전체 로컬 빌드 도구를 검사합니다. JDK 21 이상,
Node.js `^22.22.3 || ^24.15.0 || ^26.0.0`, npm 10 이상이 모두 필요합니다. 생성되는
Angular 도구가 지원하지 않는 major 또는 patch 범위는 숫자가 더 높더라도 거부합니다.

```bash
wiz-spring create ../dashboard --package com.example.dashboard

wiz-spring create ../site \
  --package com.example.site \
  --template react
```

사용할 수 있는 템플릿:

```text
angular-wiz (기본값)
angular
react
html
jsp
```

템플릿 설명은 `wiz-spring templates`로 확인할 수 있습니다.

## Docker 프로젝트 helper

[`helper/`](helper/README.md)는 `wiz-spring create`를 HTTP로 제공하는 선택적 Docker
전용 컴포넌트입니다. 프로젝트 이름, Java package, template을 HTTP로 전달하면 생성된
프로젝트를 ZIP으로 반환합니다. helper 소스와 template registry는 Maven의 `src/main`
밖에 있으므로 `wiz-spring-1.0.0.jar`에 포함되지 않으며, helper container를 시작할 때
generator JAR를 읽기 전용으로 mount합니다.

```bash
./mvnw clean package
docker compose -f helper/docker-compose.yaml up -d --build --wait

curl --fail-with-body http://127.0.0.1:8080/

curl --fail-with-body \
  -X POST \
  'http://127.0.0.1:8080/api/v1/projects?projectName=dashboard&packageName=com.example.dashboard&template=angular-wiz' \
  -o dashboard.zip
```

`GET /`는 registry 기본값과 각 template의 ID, base, 설명을 JSON으로 반환합니다.
프로젝트 생성은 JSON, form, query string 입력을 지원하며 한 요청에서는 한 방식만
사용해야 합니다. 세 방식의 예시는 [helper 안내서](helper/README.md)에 있습니다.

Helper image에 노출되는 template은 빌드 시
[`templates/registry.json`](helper/templates/registry.json)으로 결정합니다. 각 항목은
다섯 built-in base 중 하나를 선택하고 필요하면 파일 제거와 overlay를 적용할 수
있습니다. Registry에서 항목을 빼면 해당 image에서 template이 삭제됩니다. Custom
image 예시, placeholder, 경로 안전 규칙은 [helper 안내서](helper/README.md)를
참고하십시오.

## CLI 명령어

1.0 CLI는 의도적으로 기능 범위를 작게 유지합니다. 빌드와 실행은 생성기가 아니라
각 생성 프로젝트가 직접 담당합니다.

| 명령어 | 설명 |
| --- | --- |
| `create <path> --package <package> [--template <template>]` | 독립적으로 동작하는 Spring 프로젝트를 생성합니다. |
| `templates` | 사용할 수 있는 프론트엔드 템플릿을 표시합니다. |
| `service <subcommand>` | 생성된 번들을 systemd 서비스로 설치하고 관리합니다. |
| `completion <bash\|zsh>` | 셸 자동 완성 스크립트를 출력합니다. |

전체 옵션은 CLI 도움말에서 확인합니다.

```bash
wiz-spring --help
wiz-spring create --help
wiz-spring service --help
```

현재 셸에 자동 완성을 적용하려면 다음 중 하나를 실행합니다.

```bash
# Bash
source <(wiz-spring completion bash)

# Zsh
source <(wiz-spring completion zsh)
```

## 생성 프로젝트 명령어

```bash
npm ci
npm run frontend:build
npm run backend:build       # Maven clean package와 동일
npm run build               # 백엔드 + 프론트엔드
npm run dev                 # Spring + 백엔드 컴파일 watcher + 프론트엔드 watcher
npm run bundle
```

Angular WIZ는 다음 프론트엔드 전용 명령어도 제공합니다.

```bash
npm run wizbuild
npm run wizwatch
```

WIZ 컴파일러는 프로젝트의 `scripts/wizbuild.mjs`와 `scripts/wiz/*.mjs`에 포함됩니다.
Git clone 이후에는 lockfile에 기록된 의존성만 있으면 되며, `wiz-spring` JAR나 외부
WIZ 빌드 패키지를 사용하지 않습니다.

프로젝트에 포함된 Maven Wrapper로 백엔드를 직접 빌드할 수 있습니다.

```bash
./mvnw clean package
```

## 백엔드와 API 경로

생성된 백엔드는 `src/main/java` 아래의 표준 Maven/Spring 소스입니다. 소스 위치
변환, 런타임 Java 컴파일, 리플렉션 기반 앱 디스패처, 프론트엔드 메타데이터 기반
API 생성은 사용하지 않습니다.

컨트롤러에는 전역 prefix를 쓰지 않습니다.

```java
@ApiController("/dashboard")
public class DashboardController {
    @GetMapping
    public DashboardResponse dashboard() { /* ... */ }
}
```

생성된 Spring MVC 설정이 prefix를 중앙에서 적용합니다.

```yaml
app:
  api:
    prefix: ${APP_API_PREFIX:/api}
```

위 예제는 `/api/dashboard`에 매핑됩니다. 컨트롤러를 수정하지 않고
`APP_API_PREFIX=/api/v2`를 설정하면 `/api/v2/dashboard`로 바뀝니다. 여러 버전을
동시에 제공할 때 사용하는 Spring path versioning은 `app.api.versioning`에서
설정하며 `default-version`이 필요합니다. 프론트엔드는 런타임에
`/app-config.json`을 읽어 실제 client prefix를 확인합니다.

### 새 프로젝트 샘플

새로 생성한 프로젝트는 단일 endpoint 확인 화면이 아니라 바로 실행 가능한 참고
애플리케이션입니다. 표준 Spring backend에 BCrypt 세션 로그인, 사용자 5명 seed,
게시물 검색·CRUD, 프로필·비밀번호 변경, dashboard 통계, 영속 H2 데이터, 채팅 기록과
SSE stream이 포함됩니다. 다섯 frontend 템플릿 모두 같은 반응형 로그인, dashboard,
members, posts, profile, chat, light/dark theme 흐름을 제공합니다.

```text
admin@example.com / admin1234
```

샘플 source와 test는 새 프로젝트에만 주입됩니다. `--uri`와 `--path` import에는 선택한
build/API prefix 인프라만 적용되며 기존 애플리케이션 source에 demo controller나 화면을
섞지 않습니다.

import할 source는 선택한 1.0 frontend layout을 이미 따라야 합니다. Angular WIZ는
`src/app/`, Angular는 `frontend/src/{index.html,main.ts,styles.css}`, React는
`frontend/index.html`과 `frontend/src/`, HTML은 `frontend/index.html`, JSP는
`src/main/webapp/WEB-INF/jsp/`가 필요합니다. 맞지 않으면 target을 게시하기 전에
실패하며, 이전 layout을 추측하거나 자동 이동하지 않습니다.
특히 `--path`와 `--uri` import는 0.2.8 프로젝트를 이전하는 migration 명령이
아닙니다.

## 프론트엔드 식별

생성된 `package.json`에는 `wiz.frontend`가 들어 있습니다. 도구는 이 값을 가장
먼저 사용하며, 값이 없으면 `angular.json`, React 의존성,
`src/main/webapp/WEB-INF` 같은 표준 프로젝트 특성을 확인합니다. 메타데이터와
디렉터리 구조가 일치하지 않으면 다른 빌더를 임의로 선택하지 않고 실패합니다.

Angular WIZ는 사람과 AI가 편집하기 쉬운 소스 구조를 유지합니다.

```text
src/app/
src/portal/
src/route/
src/angular/
```

이 경로에는 프론트엔드 파일만 둡니다. Java 코드는 `src/main/java`에 둡니다.

## 번들과 컨테이너

`npm run bundle`은 백엔드와 프론트엔드를 clean build한 뒤 원자적으로 번들을
게시합니다.

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

원하는 proxy profile 하나를 실행합니다.

```bash
docker compose --profile nginx up -d
docker compose --profile apache2 up -d
```

Spring Boot가 executable JAR의 JSP를 지원하지 않으므로 JSP 템플릿은 executable
WAR를 사용합니다. 다른 템플릿은 executable JAR와 독립적인 프론트엔드 파일
트리를 생성합니다.

## systemd 서비스

```bash
wiz-spring service install dashboard \
  --bundle /srv/dashboard/bundle \
  --user dashboard
```

설치된 unit은 번들의 artifact를 직접 실행하며 systemd에 enable됩니다. 생성기 JAR가
삭제되어도 서버 재부팅 후 서비스를 시작할 수 있습니다. 기본 Spring profile은
`prod,bundle`이고 출력은 journald에 기록됩니다. `--profiles`로 활성 profile 목록을
바꿀 수 있습니다. 안전을 위해 root 소유 번들은 root가 아닌 `--user`를 지정하거나
`--allow-root`를 명시해야 합니다. `list`, `status`, `logs`, `start`, `stop`,
`restart`, `uninstall` 명령을 사용할 수 있습니다.

## AI 인스트럭션

모든 생성 프로젝트에는 공통 인스트럭션과 선택한 프론트엔드의 인스트럭션 하나만
들어갑니다. 다음 표는 생성기 저장소의 원본과 생성 프로젝트의 위치를 정확히
대응시킨 것입니다.

| 범위 | 생성기 원본 | 생성 프로젝트 |
| --- | --- | --- |
| 프로젝트 최상위 규칙 | [`project-common/AGENTS.md`](src/main/resources/wiz/templates/project-common/AGENTS.md) | `AGENTS.md` |
| Copilot 진입점 | [`project-common/.github/copilot-instructions.md`](src/main/resources/wiz/templates/project-common/.github/copilot-instructions.md) | `.github/copilot-instructions.md` |
| Spring 백엔드 | [`project-common/docs/ai/backend-spring.md`](src/main/resources/wiz/templates/project-common/docs/ai/backend-spring.md) | `docs/ai/backend-spring.md` |
| 빌드와 배포 | [`project-common/docs/ai/deployment.md`](src/main/resources/wiz/templates/project-common/docs/ai/deployment.md) | `docs/ai/deployment.md` |
| Angular WIZ 프론트엔드 | [`project-angular-wiz/docs/ai/frontend.md`](src/main/resources/wiz/templates/project-angular-wiz/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| Angular 프론트엔드 | [`project-angular/docs/ai/frontend.md`](src/main/resources/wiz/templates/project-angular/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| React 프론트엔드 | [`project-react/docs/ai/frontend.md`](src/main/resources/wiz/templates/project-react/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| HTML 프론트엔드 | [`project-html/docs/ai/frontend.md`](src/main/resources/wiz/templates/project-html/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| JSP 프론트엔드 | [`project-jsp/docs/ai/frontend.md`](src/main/resources/wiz/templates/project-jsp/docs/ai/frontend.md) | `docs/ai/frontend.md` |

선택한 프론트엔드 overlay가 `docs/ai/frontend.md`를 제공하며 다른 프론트엔드
가이드는 복사되지 않습니다. 프로젝트 내부 MCP 런타임은 설정하지 않습니다.

## 개발

```bash
./mvnw test
```

생성기 테스트는 각 템플릿을 임시 디렉터리에 생성합니다. 릴리스 전에는 생성된 Maven
프로젝트, 선택한 프론트엔드 빌드, 번들 검증도 함께 smoke test해야 합니다.

## 라이선스

WIZ Spring은 [MIT License](LICENSE)로 배포됩니다.
