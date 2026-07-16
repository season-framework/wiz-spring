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
java -jar "$jar" codex --root <workspace> --runtime-jar "$jar"
```
