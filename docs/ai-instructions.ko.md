[English](ai-instructions.md) | [한국어](ai-instructions.ko.md)

# AI 인스트럭션

모든 생성 프로젝트에는 공통 프로젝트, Spring 백엔드, 배포 계약과 선택한 프론트엔드
계약 하나가 들어갑니다. 이 파일들은 AI coding tool과 사람에게 현재 `1.1.1`
프로젝트 계약을 설명하며 MCP runtime을 추가하지 않습니다.

인스트럭션의 기준은 Java 25, Spring Boot `4.1.1`, Spring Framework `7.0.9`
(Boot BOM 관리), springdoc `3.1.0`, Maven `3.9.15`, Node.js
`^22.22.3 || ^24.15.0`입니다. 프론트엔드별 인스트럭션은
고정된 Angular 22 또는 React 19 toolchain도 명시합니다. 독립적인 생성
프로젝트를 이후에 버전업했다면 해당 프로젝트의 `pom.xml`, `package.json`,
lockfile이 최종 기준입니다.

| 범위 | Generator 원본 | 생성 프로젝트 |
| --- | --- | --- |
| 프로젝트 최상위 계약 | [`project-common/AGENTS.md`](../src/main/resources/wiz/templates/project-common/AGENTS.md) | `AGENTS.md` |
| Copilot 진입점 | [`project-common/.github/copilot-instructions.md`](../src/main/resources/wiz/templates/project-common/.github/copilot-instructions.md) | `.github/copilot-instructions.md` |
| Spring 백엔드 | [`project-common/docs/ai/backend-spring.md`](../src/main/resources/wiz/templates/project-common/docs/ai/backend-spring.md) | `docs/ai/backend-spring.md` |
| 빌드와 배포 | [`project-common/docs/ai/deployment.md`](../src/main/resources/wiz/templates/project-common/docs/ai/deployment.md) | `docs/ai/deployment.md` |
| Angular WIZ 프론트엔드 | [`project-angular-wiz/docs/ai/frontend.md`](../src/main/resources/wiz/templates/project-angular-wiz/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| Angular 프론트엔드 | [`project-angular/docs/ai/frontend.md`](../src/main/resources/wiz/templates/project-angular/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| React 프론트엔드 | [`project-react/docs/ai/frontend.md`](../src/main/resources/wiz/templates/project-react/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| HTML 프론트엔드 | [`project-html/docs/ai/frontend.md`](../src/main/resources/wiz/templates/project-html/docs/ai/frontend.md) | `docs/ai/frontend.md` |
| JSP 프론트엔드 | [`project-jsp/docs/ai/frontend.md`](../src/main/resources/wiz/templates/project-jsp/docs/ai/frontend.md) | `docs/ai/frontend.md` |

선택한 프론트엔드 overlay만 `docs/ai/frontend.md`를 제공하며 다른 프론트엔드 계약은
복사하지 않습니다. 인스트럭션을 변경할 때는 위 표를 원본 위치의 기준으로 사용합니다.

## 인스트럭션 변경

1. 공통 또는 프론트엔드 범위에 해당하는 generator 원본을 수정합니다.
2. 버전, 경로, 빌드 명령, API 규칙을 실제 템플릿 코드와 일치시킵니다.
   README만 또는 인스트럭션만 따로 바꾸지 않습니다.
3. `./mvnw test`를 실행합니다. Generator test가 임시 프로젝트를 만들면서
   인스트럭션과 버전 경계를 검증합니다.
4. `scripts/verify-documentation.sh`를 실행하고 해당 템플릿을 생성해 README와
   결과 인스트럭션을 직접 확인합니다.
5. 플랫폼이나 의존성 버전을 바꾸었다면 `scripts/verify-templates.sh`를 실행합니다.

템플릿 구조는 [프로젝트 생성](project-generation.ko.md)을, 인스트럭션이 설명하는
계약은 [빌드와 배포](build-and-deployment.ko.md)를 참고하십시오.
