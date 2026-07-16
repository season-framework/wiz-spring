# Context Gathering Prompt

WIZ Spring 작업 전 확인 순서:

1. `wiz-spring/README.md`에서 현재 지원 범위를 확인한다.
2. 관련 runtime source를 `wiz-spring/src/main/java/com/wiz/**`에서 찾는다.
3. sample 구현은 `wiz-spring/src/main/resources/wiz/templates/default-project-java`에서 확인한다.
4. CLI 동작은 `wiz-spring/src/main/java/com/wiz/cli`와 `ProjectScaffoldService`를 기준으로 확인한다.
5. 변경 후 `scripts/contract-spring-http.sh`, `scripts/e2e-spring-smoke.sh`, `wiz-spring ./mvnw test` 중 영향을 받는 검증을 고른다.
