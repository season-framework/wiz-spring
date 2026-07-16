# Development Principles

## 계층 분리

- UI: `view.pug`, `view.ts`, `view.scss`
- App API: 화면에 가까운 요청 조립, validation, response envelope
- Controller: 인증/권한/공통 response data
- Model/Struct: domain 규칙, persistence, 외부 연동
- Route: 앱 API와 별도인 HTTP endpoint
- Runtime core: app 공통 실행 기반만 담당

## Java/Spring 경계

- 앱별 DB, SMTP, SDK는 workspace `pom.xml`과 app source에 둔다.
- core jar에 app domain 의존성을 추가하지 않는다.
- Servlet session은 `wiz.session()`으로만 다루고, auth 정책은 `AuthService` 확장으로 모은다.
- redirect는 `wiz.redirect.policy` 설정을 검토한다. 운영에서는 `local-only` 또는 allowlist를 우선한다.

## Source 관리

- 생성 산출물을 직접 수정하지 않는다.
- package 선언을 생략하면 build가 `wiz.java.package-root` 기준 package로 rewrite한다. 명시 package를 쓰는 경우 handler FQCN과 import를 함께 관리한다.
- public API method는 구조가 고정된 응답에는 작은 record/class DTO를 우선 사용한다. `Map`은 검색 필터, 동적 설정, 자유 형식 metadata처럼 key가 실행 시점에 달라지는 payload에만 사용하고, 복잡한 변환은 Struct/service로 내린다.

## 보안

- path segment와 파일 경로는 `SafePath` 또는 workspace context root를 기준으로 제한한다.
- query/form/json 입력은 문자열로 들어온다고 보고 type 변환과 범위 검사를 명시한다.
- role check는 정확히 비교한다. substring membership 방식으로 권한을 판단하지 않는다.
- secret이 포함될 수 있는 config 값을 로그로 남기지 않는다.
- 실제 `config/application*.yml`의 Git ignore 규칙을 유지하고, 공유용 `application*.example.yml`에는 secret이나 credential을 기록하지 않는다.
- standalone jar와 runtime bundle에는 실제 config가 포함되므로 artifact를 공개하기 전에 민감 값 포함 여부를 확인한다.
