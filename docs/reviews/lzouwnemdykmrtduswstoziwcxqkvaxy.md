# WIZ Spring build 산출물 구조 전환 기록

- Review ID: `lzouwnemdykmrtduswstoziwcxqkvaxy`
- 제목: build 이후 산출물 검토
- 대상: `wiz-spring`, `wiz-spring-instruction`
- 결정: 원본 WIZ `src/**` 구조는 유지하고, `build/**`는 외부에서 보아도 깨끗한 Spring Boot/Maven project처럼 보이도록 전환한다.

## 반영 결과

`build/`의 공개 구조를 아래처럼 정리했다.

```text
build/
  pom.xml
  src/
    main/
      java/
      resources/
  target/
    classes/
    dependency/
    app-api.jar
    frontend/
    bom.json
  .wiz/
    source/
```

- `build/src/main/java`: `api.java`, `socket.java`, `route.java`, `src/controller`, `src/model`, portal model/controller source를 Java package 구조로 재구성한 공개 source.
- `build/src/main/resources`: workspace `config/**`를 복사한 runtime resource.
- `build/target/classes`: Java compile output.
- `build/target/app-api.jar`: project Java handler jar.
- `build/target/dependency`: workspace `pom.xml` 기반 Maven runtime dependencies.
- `build/target/frontend`: Angular build output.
- `build/.wiz/source`: WIZ app/portal/Angular source를 평탄화한 내부 staging. 외부 공유용 구조가 아니며 직접 수정하지 않는다.

`bundle/` runtime 계약은 유지했다. runtime은 계속 `bundle/classes`, `bundle/app-api.jar`, `bundle/src/**`, `bundle/www`를 읽는다.

## 구현 변경점

- `ProjectBuildLayout`을 추가해 build staging, generated source, target artifact 경로를 한 곳에서 관리한다.
- `ProjectBuildService`가 WIZ source staging을 `build/.wiz/source`로 옮기고, generated Java source를 `build/src/main/java`에 쓴다.
- `ProjectBuildService`가 `build/pom.xml`, `build/src/main/resources`, `build/target/classes`, `build/target/app-api.jar`, `build/target/dependency`를 생성한다.
- generated Java package를 Spring 계층형으로 정리했다. App API는 `web.api`, route는 `web.route`, socket은 `realtime.socket`, controller hook은 `security.guard`, model은 `application.model`/`application.service`/`domain.entity`, portal model은 `module.{package}...` 아래로 생성한다.
- 기존 `{packageRoot}.api`, `{packageRoot}.socket`, `{packageRoot}.route`, `{packageRoot}.controller`, `{packageRoot}.model`, `{packageRoot}.portal.*.model` FQCN은 build/metadata/runtime lookup 단계에서 새 package로 정규화한다.
- `AngularBuildService`와 `AngularSourceStagingService`가 내부 staging 경로를 사용하고 frontend output을 `build/target/frontend`로 복사한다.
- `SupplyChainManifestService`가 CycloneDX BOM을 `build/target/bom.json`에 쓴다.
- MCP dependency info는 `build/target/dependency`를 가리킨다.

## 문서 반영

- `README.md`: workspace/build 구조와 공개/내부 build 경계를 갱신했다.
- `docs/*`: migration, build contract, user manual, e2e smoke 문서의 build 경로를 갱신했다.
- `wiz-spring-instruction`: project structure, architecture, usage guide, Copilot instruction에 새 build layout을 반영했다.

## 판단

이번 변경은 "사용자가 작성하는 원본 source 구조까지 Spring 표준으로 바꾸는 전면 전환"은 아니다. WIZ의 app-local 개발 방식은 유지한다.

대신 외부에서 build 산출물을 열어볼 때 `pom.xml`, `src/main/java`, `src/main/resources`, `target` 중심의 표준 Maven/Spring Boot project처럼 보이도록 공개 build surface를 분리했다. WIZ 전용 staging은 숨김 경로인 `build/.wiz/source`로 이동했다. generated package 명칭도 Spring 계층형으로 정리해 build 산출물에서 WIZ 전용 `api`/`controller`/`model` package 용어가 전면에 드러나지 않게 했다.

## 남은 리스크

- generated handler는 Spring bean scan 대상이 아니라 WIZ runtime classloader/reflection으로 실행된다. 외부 제출 시 generated source라는 설명은 함께 제공하는 편이 안전하다.
