<div align="center">

# WIZ Spring

**프로젝트에 필요한 프론트엔드 구조와 표준 Spring Boot 백엔드를 생성합니다.**

[![Release 1.1.1](https://img.shields.io/badge/release-1.1.1-2563eb)](release-log/1.1.1.md)
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

## WIZ Spring을 사용하는 이유

- **표준 백엔드** — Java는 `src/main/java`에 있고 Maven으로 직접 빌드합니다. WIZ
  백엔드 변환이나 runtime dispatcher가 없습니다.
- **얕은 도메인 구조** — Controller는 type-safe Root `Struct`로 진입하고 각 기능의
  동작과 persistence는 하나의 `model/<feature>` package에 함께 둡니다.
- **다섯 가지 프론트엔드** — 표준 프론트엔드를 선택하거나 사람과 AI가 빠르게
  편집하도록 설계된 Angular WIZ 구조를 유지할 수 있습니다.
- **독립 프로젝트** — `create` 이후 빌드에는 generator JAR나 외부 WIZ NPM package가
  필요하지 않으며 `.wiz` 디렉터리도 만들지 않습니다.
- **하나의 배포 workflow** — 모든 템플릿이 watch, 통합 build, bundle, Docker
  Compose, Nginx/Apache2, 선택적 systemd service를 제공합니다.

## 빠른 시작

요구 사항: full JDK 25+, Node.js `^22.22.3 || ^24.15.0`(LTS만 지원), npm 10+.

```bash
./mvnw clean package

java -jar target/wiz-spring-1.1.1.jar create ../dashboard \
  --package com.example.dashboard

cd ../dashboard
npm ci
npm run dev
```

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

내장 설명은 `java -jar target/wiz-spring-1.1.1.jar templates`로 확인할 수 있습니다.

## 생성 프로젝트 workflow

```bash
npm run dev       # Spring + 백엔드 compile watcher + 프론트엔드 watcher
npm run build     # 백엔드와 프론트엔드 clean build
npm run bundle    # 배포 artifact, proxy 설정, Compose, checksum
```

모든 템플릿은 `frontend:build`, `backend:build`도 제공합니다. Angular WIZ에는
`wizbuild`, `wizwatch`가 추가되며 compiler source가 생성 프로젝트에 포함됩니다.

## CLI

| 명령 | 용도 |
| --- | --- |
| `create` | 새 프로젝트를 생성하거나 호환되는 1.0 source를 import합니다. |
| `templates` | 프론트엔드 템플릿을 표시합니다. |
| `service` | 생성된 번들을 systemd로 설치하고 관리합니다. |
| `completion` | Bash 또는 Zsh 자동 완성을 생성합니다. |

전체 옵션은 `<command> --help`에서 확인하십시오.

## HTTP 프로젝트 Helper

선택적 [Docker 프로젝트 Helper](helper/README.ko.md)는 HTTP로 프로젝트를 생성해 ZIP으로
반환합니다. Build-time registry로 템플릿을 추가·수정·삭제할 수 있으며 Helper는
`wiz-spring-1.1.1.jar`에 포함되지 않습니다.

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
scripts/verify-templates.sh
```

테스트는 임시 디렉터리에 모든 프론트엔드 템플릿을 생성합니다. Helper 전용 검증은
[Helper 운영 문서](helper/docs/operations.ko.md)를 참고하십시오. 템플릿 검증 스크립트는
생성된 5개 템플릿 모두에 대해 설치, 보안 감사, 테스트, 빌드, 번들을 수행하며 생성
프로젝트와 같은 JDK 및 Node.js toolchain이 필요합니다.

버그와 기능 요청은 [GitHub Issues](https://github.com/season-framework/wiz-spring/issues)에서
관리합니다. WIZ Spring은 [MIT License](LICENSE)로 배포합니다.
