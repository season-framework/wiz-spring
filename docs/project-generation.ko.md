[English](project-generation.md) | [한국어](project-generation.ko.md)

# 프로젝트 생성

WIZ Spring은 선택한 프론트엔드 하나와 표준 Spring Boot 프로젝트를 생성합니다.
생성 후에는 generator가 필요하지 않습니다. 빌드, watch, 실행, bundle 명령은 생성된
저장소에 포함됩니다.

## 요구 사항

- `javac`를 포함한 full JDK 21 이상
- Node.js `^22.22.3 || ^24.15.0 || ^26.0.0`
- npm 10 이상
- `--uri`로 import할 때 `PATH`에서 실행할 수 있는 Git

`create`는 target에 파일을 쓰기 전에 전체 toolchain을 검사합니다. 생성되는 Angular
toolchain이 지원하지 않는 Node.js 범위는 숫자가 더 높더라도 거부합니다.

소스에서 generator를 빌드합니다.

```bash
./mvnw clean package
alias wiz-spring='java -jar /absolute/path/to/wiz-spring/target/wiz-spring-1.0.0.jar'
```

## CLI

| 명령 | 용도 |
| --- | --- |
| `create <path> --package <package> [--template <id>]` | 독립 프로젝트를 생성하거나 import합니다. |
| `templates` | 내장 프론트엔드 템플릿을 표시합니다. |
| `service <subcommand>` | 생성된 번들을 systemd 서비스로 관리합니다. |
| `completion <bash\|zsh>` | 셸 자동 완성을 생성합니다. |

전체 옵션은 `wiz-spring <command> --help`가 기준입니다. 현재 셸에는
`source <(wiz-spring completion bash)` 또는 `source <(wiz-spring completion zsh)`로
자동 완성을 적용할 수 있습니다.

## 템플릿

기본값은 `angular-wiz`입니다.

| ID | 프론트엔드 소스 | 특징 |
| --- | --- | --- |
| `angular-wiz` | `src/app`, `src/portal`, `src/route`, `src/angular` | 내장 WIZ compiler와 사람·AI가 편집하기 쉬운 구조를 사용하는 Angular |
| `angular` | `frontend/src` | 표준 Angular 애플리케이션 |
| `react` | `frontend/src` | Vite로 빌드하는 React 애플리케이션 |
| `html` | `frontend` | 정적 HTML, CSS, JavaScript |
| `jsp` | `src/main/webapp/WEB-INF/jsp` | executable WAR를 사용하는 Spring MVC/JSP |

모든 템플릿의 백엔드는 `src/main/java` 아래의 표준 Maven 구조를 사용합니다. `.wiz`
디렉터리나 외부 WIZ 빌드 패키지를 만들지 않습니다. 루트 `package.json`은 선택한
프론트엔드를 `wiz.frontend`에 기록하며, 빌드 도구는 이 값과 소스 구조가 일치하는지
검증합니다.

Target directory 이름은 Maven/npm artifact ID를 만들 때 소문자로 변환합니다.
`[a-z0-9_.-]` 이외의 연속 문자는 `-`로 바꾸고 앞뒤 separator는 제거합니다. Java
package는 Java 21 identifier로 구성하므로 segment에 `-`를 사용할 수 없으며 Java
keyword 또는 `java` namespace도 허용되지 않습니다.

## 새 프로젝트 생성

```bash
wiz-spring create ../dashboard --package com.example.dashboard

wiz-spring create ../portal \
  --package com.example.portal \
  --template react
```

새 프로젝트에는 바로 실행할 수 있는 참고 애플리케이션이 포함됩니다. BCrypt 세션
로그인, 사용자 5명, 게시물과 검색, 프로필·비밀번호 변경, dashboard 통계, 영속 H2,
채팅 기록과 SSE stream을 제공합니다. 모든 프론트엔드는 같은 반응형 화면과
light/dark theme을 구현합니다.

```text
admin@example.com / admin1234
```

실행 중인 프로젝트의 API 문서는 `/swagger-ui`에서 확인할 수 있습니다.

## 기존 소스 import

Import source는 하나만 사용하고 템플릿을 반드시 명시합니다.

```bash
wiz-spring create ../imported \
  --package com.example.imported \
  --template angular \
  --path /absolute/path/to/source

wiz-spring create ../imported \
  --package com.example.imported \
  --template react \
  --uri https://example.com/team/project.git
```

Source는 이미 다음 1.0 계약을 따라야 합니다.

- Java source가 있다면 요청한 package의 `src/main/java` 아래에 있습니다.
- `@SpringBootApplication`이 없거나, 요청한 package root의 `Application.java`에 정확히
  하나 있습니다. 없으면 generator가 주입합니다.
- 프론트엔드가 위 표의 선택한 템플릿 source path를 사용합니다.

Target을 게시하기 전에 import를 검증합니다. 과거 버전을 탐지하거나 프론트엔드를
추측하지 않으며, 호환되지 않는 소스를 이동하지 않습니다. 1.0 빌드 인프라가 교체한
표준 파일은 수동 검토를 위해 `replaced-originals/`에 보관합니다. Import 프로젝트는
의존성 상태를 맞추기 위해 `npm install`을 한 번 실행하고 lockfile을 commit한 뒤
이후부터 `npm ci`를 사용하십시오.

Import에는 새 프로젝트용 샘플을 주입하지 않습니다. 0.2.x 애플리케이션을 옮기기
전에는 [1.0 호환성](compatibility.ko.md)을 확인하십시오.

## 다음 문서

- [빌드, 실행, 번들, 배포](build-and-deployment.ko.md)
- [AI 인스트럭션 구조](ai-instructions.ko.md)
- [HTTP 프로젝트 Helper](../helper/README.ko.md)
