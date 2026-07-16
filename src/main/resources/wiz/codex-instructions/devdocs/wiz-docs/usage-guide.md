# Usage Guide


WIZ Spring은 기존 WIZ 프로젝트 구조를 유지하되 서버 런타임을 Java 21, Spring Boot 4.0.6, Maven 기반 실행 jar로 제공한다.
프로젝트 백엔드는 `api.java`, `route.java`, `socket.java`, `src/controller/*.java`, `src/model/**/*.java`가 source of truth이고,
`build/`와 `bundle/`은 `wiz-spring build`가 매번 재생성하는 산출물이다.

`build/`의 공개 구조는 Spring Boot/Maven 관례를 따른다. 생성 Java source는 `build/src/main/java`, config resource는 `build/src/main/resources`, compile output과 dependency는 `build/target/**` 아래에 둔다. WIZ 내부 staging은 `build/.wiz/source`에 있으며 직접 수정하지 않는다.

기본 개발 흐름:

```bash
cd /root/workspace/wiz-java/wiz-spring
./mvnw clean package

jar=/root/workspace/wiz-java/wiz-spring/target/wiz-spring-0.2.6.jar
workspace=/tmp/wiz-spring-demo

rm -rf "$workspace"
java -jar "$jar" create "$workspace" --package com.example.demo
java -jar "$jar" run --root "$workspace" --port 3000
```

검증 명령:

```bash
cd /root/workspace/wiz-java
bash scripts/e2e-spring-smoke.sh
bash scripts/contract-spring-http.sh

cd /root/workspace/wiz-java/wiz-spring
./mvnw test
```


## Standalone jar

```bash
java -jar "$jar" jar --root "$workspace" --output /tmp/wiz-app.jar
java -jar /tmp/wiz-app.jar
```

Standalone jar는 내장 app bundle을 사용자 cache에 풀고 서버를 시작한다. 인자 없이 실행할 때 기본 profile은 `prod`다.

## Configuration profile

`application.yml`은 항상 읽고 선택된 profile의 `application-<profile>.yml`을 그 다음에 병합한다. `wiz-spring run`은 기본 `dev`, 인자 없는 standalone jar는 기본 `prod`다. `wiz-spring run --profile <name>`으로 다른 profile을 명시할 수 있다.

생성된 실제 `application*.yml`은 `.gitignore` 대상이고 `application*.example.yml`이 공유용이다. clone 후 example을 실제 파일명으로 복사한다. 실제 config는 standalone jar에 포함되므로 배포 전 민감 값 포함 여부를 확인한다.

## Dependency

- Java dependency: `pom.xml`
- Frontend dependency: `src/angular/package.json`
- Runtime config: `config/application.yml`, `config/application-<profile>.yml`
- Version-control-safe config: `config/application*.example.yml`
