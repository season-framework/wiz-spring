# Command Reference

## Runtime build

```bash
cd /root/workspace/wiz-java/wiz-spring
./mvnw clean package
```

## Workspace

```bash
java -jar "$jar" create <workspace> --package com.example.demo
java -jar "$jar" create <workspace> --package com.example.demo --skip-build
java -jar "$jar" create <workspace> --package com.example.demo --path /path/to/source
java -jar "$jar" create <workspace> --package com.example.demo --uri https://example.com/repo.git
```

## Build/run/package

```bash
java -jar "$jar" build --root <workspace> --clean
java -jar "$jar" build --root <workspace> --package com.example.renamed
java -jar "$jar" build --root <workspace> --phase reconstruct
java -jar "$jar" build --root <workspace> --phase compile
java -jar "$jar" run --root <workspace> --port 3000
java -jar "$jar" run --root <workspace> --dry-run
java -jar "$jar" jar --root <workspace> --output /tmp/wiz-app.jar
java -jar "$jar" bundle --root <workspace> --output /tmp/wiz-bundle
```

`build --package`는 build 이력과 관계없이 `wiz.java.package-root`, workspace `pom.xml`과 WIZ Java source의 package 참조를 변경한다. package가 달라지면 기존 generated Spring tree와 bundle을 지우는 clean build가 자동 적용된다. 기존 standalone JAR은 새 package로 다시 생성한다.

## Ops

```bash
java -jar "$jar" kill --dry-run
java -jar "$jar" service list
java -jar "$jar" service regist demo 3000 --root <workspace> --dry-run
java -jar "$jar" mcp --root <workspace>
```

`create`는 기본 template, `--path`, `--uri` 모두 source 준비 후 `.codex` MCP 설정과 내장 `.github` 인스트럭션을 자동으로 설치한다. 별도 `codex` 하위 명령은 사용하지 않는다. import source의 관리 대상 파일은 현재 내장본으로 갱신하고 `.github/custom/` 같은 비관리 파일은 보존한다.

## Shell completion

`completion`은 현재 CLI command, subcommand와 option을 반영한 source용 script를 출력한다.

```bash
# Bash
source <(java -jar "$jar" completion bash)

# Zsh
source <(java -jar "$jar" completion zsh)
```

`Tab`을 누르면 입력 커서를 유지한 채 입력 줄 아래에 root command 설명 또는 현재 command의 usage, argument와 option 설명을 표시한다. Bash에서는 같은 위치에서 `Tab`을 다시 눌러도 도움말을 유지하며 Zsh에서는 네이티브 목록에 도움말과 후보를 함께 표시한다. 실제 `--help`와 같은 bold, option color, parameter emphasis를 적용한다. 설명 패널은 `WIZ_SPRING_COMPLETION_HELP=false`, 색상은 `WIZ_SPRING_COMPLETION_COLOR=false` 또는 `NO_COLOR=1`로 끌 수 있다.

지속 적용할 때는 출력 파일을 shell 시작 설정에서 source한다. Zsh용 출력은 필요한 경우 `compinit`과 `bashcompinit`을 초기화한다.
