<div align="center">

# WIZ Spring

**프로젝트에 필요한 프론트엔드 구조와 표준 Spring Boot 백엔드를 생성합니다.**

[![Release 1.2.2](https://img.shields.io/badge/release-1.2.2-2563eb)](release-log/1.2.2.md)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-e76f00)](pom.xml)
[![MIT License](https://img.shields.io/badge/license-MIT-16a34a)](LICENSE)

[English](README.md) · [한국어](README.ko.md)

</div>

WIZ Spring은 Spring Boot 4와 Angular WIZ, Angular, React, HTML 또는 JSP를 조합하는
프로젝트 생성기이자 선택적 systemd 서비스 관리자입니다. Generator JAR는 프로젝트
생성 또는 서비스 관리에만 필요하며, Maven·프론트엔드·watch·build·bundle·runtime
workflow는 생성된 프로젝트가 직접 소유합니다.

> [!IMPORTANT]
> WIZ Spring 1.0은 0.2.8을 포함한 0.2.x와 완전히 분리된 새 구조입니다. 기존
> workspace를 인플레이스 마이그레이션하지 않습니다. 애플리케이션을 옮기기 전에
> [1.0 호환성 문서](docs/compatibility.ko.md)를 확인하십시오.

## 1.2.2 핵심 변경

- `npm run bundle`은 이제 executable `application.jar`(JSP는 WAR), 프론트엔드
  `public/`, `.env`, `docker-compose.yaml`만 게시합니다.
- Docker Compose는 빌드된 Spring 애플리케이션 하나만 실행합니다. Nginx와 Apache HTTP
  Server는 Compose 서비스와 bundle에서 제거하고, 수정 가능한 host 설정 예시는 생성
  프로젝트의 `deploy/` 디렉터리에 남겼습니다.
- Production service 설치는 새 root archive를 자동 인식하며, 1.2.1에서 생성한
  manifest/checksum bundle도 기존과 같이 엄격하게 검증해 실행합니다.

## 1.2.2 플랫폼 기준

WIZ Spring `1.2.2`가 생성하고 검증하는 정확한 stack은 다음과 같습니다. 단순한 최소
호환 버전이 아니라 템플릿에 고정된 버전입니다.

| 계층 | 1.2.2 기준 |
| --- | --- |
| Generator와 생성 프로젝트 버전 | `1.2.2` |
| Java | release `25`; full JDK 25 이상 필요 |
| Spring 백엔드 | Spring Boot `4.1.1`, Boot 관리 Spring Framework `7.0.9`, springdoc `3.1.0` |
| 빌드 도구 | Maven Wrapper `3.9.15`, npm `8+` |
| Node.js | `^22.22.3 || ^24.15.0 || >=26.0.0` |
| Angular 템플릿 | Angular `22.1.4`, Angular CLI/build `22.1.6`, TypeScript `6.0.3` |
| React 템플릿 | React `19.2.8`, Vite `8.2.2` |

Generator를 올려도 이미 생성된 프로젝트를 자동으로 다시 쓰지 않습니다. 이 기준을
적용하려면 새 `1.2.2` 프로젝트를 생성하거나 기존 프로젝트의 `pom.xml`,
`package.json`, lockfile, `docs/ai` 인스트럭션을 함께 명시적으로 갱신하십시오.

## WIZ Spring을 사용하는 이유

- **표준 백엔드** — Java는 `src/main/java`에 있고 Maven으로 직접 빌드합니다. WIZ
  백엔드 변환이나 runtime dispatcher가 없습니다.
- **얕은 도메인 구조** — Controller는 type-safe Root `Struct`로 진입하고 각 기능의
  동작과 persistence는 하나의 `model/<feature>` package에 함께 둡니다.
- **다섯 가지 프론트엔드** — 표준 프론트엔드를 선택하거나 사람과 AI가 빠르게
  편집하도록 설계된 Angular WIZ 구조를 유지할 수 있습니다.
- **독립 프로젝트** — `create` 이후 빌드에는 generator JAR나 외부 WIZ NPM package가
  필요하지 않으며 `.wiz` 디렉터리도 만들지 않습니다.
- **하나의 배포 workflow** — 모든 템플릿이 watch, 통합 build, 최소 bundle,
  애플리케이션 전용 Docker Compose, proxy 예시, 선택적 systemd를 제공합니다.

## 빠른 시작

생성 프로젝트 빌드 요구 사항: full JDK 25+, Node.js
`^22.22.3 || ^24.15.0 || >=26.0.0`, npm 8+.

```bash
./mvnw clean package

java -jar target/wiz-spring-1.2.2.jar create ../dashboard \
  --package com.example.dashboard

cd ../dashboard
npm ci
npm run dev
```

생성된 프로젝트에는 바로 사용할 수 있는 root `.env`가 이미 포함됩니다. 기본값을
바꿀 때 이 파일을 직접 편집합니다.

`create`는 full JDK만 필수로 검사하며 Node.js와 npm 검사는 안내용입니다. 도구가
없거나 생성 프로젝트의 범위를 벗어난 버전이어도 경고만 표시하고 프로젝트는
생성합니다. `npm ci`를 실행하기 전에는 호환되는 프론트엔드 toolchain을 설치하십시오.

기본 템플릿은 `angular-wiz`입니다. 다른 프론트엔드는 `--template react`처럼
지정합니다. Target directory 이름은 소문자 Maven/npm artifact ID로 정규화되므로
프로젝트 이름에는 `-`를 사용할 수 있습니다. Java package segment는 유효한 Java
identifier여야 하므로 `-`를 사용할 수 없습니다.

## 템플릿

| ID | 적합한 용도 |
| --- | --- |
| `angular-wiz` | 내장 WIZ source 구조와 compiler를 사용하는 Angular, 기본값 |
| `angular` | 표준 Angular 애플리케이션 |
| `react` | Vite로 빌드하는 React 애플리케이션 |
| `html` | 정적 HTML, CSS, JavaScript |
| `jsp` | 서버 렌더링 Spring MVC/JSP 애플리케이션 |

내장 설명은 `java -jar target/wiz-spring-1.2.2.jar templates`로 확인할 수 있습니다.

## 생성 프로젝트 workflow

```bash
npm run dev       # Spring + 백엔드 compile watcher + 프론트엔드 watcher
npm run build     # 백엔드와 프론트엔드 clean build
npm run bundle    # archive, frontend, .env, 애플리케이션 전용 Compose
```

모든 템플릿은 `frontend:build`, `backend:build`도 제공합니다. Angular WIZ에는
`wizbuild`, `wizwatch`가 추가되며 compiler source가 생성 프로젝트에 포함됩니다.
개발 명령은 프론트엔드 watcher 결과를 Spring 정적 산출물에 기록하고 백엔드는
DevTools로 재기동하므로, 일반적인 소스 수정에는 명령을 다시 시작할 필요가 없습니다.
모든 프로젝트 npm script는 root `.env`를 읽습니다. 생성한 배포 bundle은 JDK만 설치된
서버로 복사해 `.env`를 export한 뒤 root archive를 `java -jar`로 시작할 수 있습니다.
설치된 systemd service도 runtime에 WIZ Spring이나 build toolchain을 요구하지 않습니다.

## CLI

| 명령 | 용도 |
| --- | --- |
| `create` | 새 프로젝트를 생성하거나 호환되는 1.0 source를 import합니다. |
| `templates` | 프론트엔드 템플릿을 표시합니다. |
| `service` | 생성 프로젝트의 실시간 개발 모드 또는 production 번들을 systemd로 관리합니다. |

전체 옵션은 `<command> --help`에서 확인하십시오.

## 개발환경 Docker 이미지

WIZ Spring 1.2.2 개발 이미지는 생성 프로젝트, 프론트엔드 의존성, Maven cache와
JDK/Node.js/Codex 도구를 `/opt/app`에 포함합니다. `VOLUME`을 선언하지 않으므로 host
bind mount 없이 기본 명령인 `npm run dev`를 바로 시작합니다. 기본 `1.2.2` 태그는
`angular-wiz`이며, `1.2.2-angular-wiz`, `1.2.2-angular`, `1.2.2-react`,
`1.2.2-html`, `1.2.2-jsp` 태그로 각 템플릿을 선택할 수 있습니다.

```bash
docker run --detach --name wiz-spring-dev \
  --publish 8080:8080 \
  --env WIZ_ENABLE_SSH=false \
  registry.nanoha.kr/kwon3286/wiz-spring:1.2.2-react
```

저장소의 [`docker-compose.yaml`](docker-compose.yaml)은 기본 개발 이미지와 PostgreSQL
18, Redis 8을 함께 시작합니다. 데이터는 named volume에 유지되며 publish하는 모든
포트는 기본적으로 `127.0.0.1`에만 bind합니다.

```bash
SSH_PASSWORD='replace-this-password' docker compose up --detach
docker compose ps
ssh -p 2222 root@127.0.0.1
```

개발 컨테이너에는 PostgreSQL 접속값이 `POSTGRES_HOST`, `POSTGRES_PORT`,
`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`로 전달되고, Redis 접속값은
`REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`로 전달됩니다. 생성된 샘플 애플리케이션은
datasource와 dependency를 명시적으로 변경하기 전까지 H2를 계속 사용합니다.

다른 프론트엔드 이미지는 `WIZ_SPRING_IMAGE`로 선택합니다. Host 포트는
`WIZ_HTTP_PORT`, `WIZ_SSH_PORT`, `POSTGRES_PORT`, `REDIS_PORT`로 바꿀 수 있고,
credential은 로컬 `.env` 파일에 둘 수 있습니다. 기본 credential은 로컬 개발
전용입니다. `docker compose down`은 묶음을 중지하고, 데이터까지 제거하려면
`docker compose down --volumes`를 사용합니다.

Compose 없이 SSH를 사용하려면 22번 포트를 publish하고 `SSH_PASSWORD`를 runtime
환경으로 전달합니다. 이미지는 `linux/amd64` 기준입니다. 전체 변형을 다시 검증하고
push하려면 `scripts/build-dev-images.sh all --push`를 실행합니다.

## HTTP 프로젝트 Helper

선택적 [Docker 프로젝트 Helper](helper/README.ko.md)는 HTTP로 프로젝트를 생성해 ZIP으로
반환합니다. Build-time registry로 템플릿을 추가·수정·삭제할 수 있으며 Helper는
`wiz-spring-1.2.2.jar`에 포함되지 않습니다.

## 문서

| 문서 | 내용 |
| --- | --- |
| [프로젝트 생성](docs/project-generation.ko.md) | 요구 사항, 템플릿, import, 샘플 애플리케이션, CLI |
| [빌드와 배포](docs/build-and-deployment.ko.md) | 프로젝트 script, API prefix, bundle, Compose, systemd |
| [1.0 호환성](docs/compatibility.ko.md) | 0.2.x에서 지원하는 전환 방식 |
| [AI 인스트럭션](docs/ai-instructions.ko.md) | 공통 및 템플릿별 인스트럭션 원본 |
| [HTTP Helper](helper/README.ko.md) | API 사용, custom registry, container 운영 |
| [릴리스 노트](release-log/README.md) | 버전 이력 |

## 개발

```bash
./mvnw test
scripts/verify-documentation.sh
scripts/verify-templates.sh
```

테스트는 임시 디렉터리에 모든 프론트엔드 템플릿을 생성합니다. Helper 전용 검증은
[Helper 운영 문서](helper/docs/operations.ko.md)를 참고하십시오. 템플릿 검증 스크립트는
생성된 5개 템플릿 모두에 대해 설치, 보안 감사, 테스트, 빌드, 번들을 수행하며 생성
프로젝트와 같은 JDK 및 Node.js toolchain이 필요합니다.

버그와 기능 요청은 [GitHub Issues](https://github.com/season-framework/wiz-spring/issues)에서
관리합니다. WIZ Spring은 [MIT License](LICENSE)로 배포합니다.
