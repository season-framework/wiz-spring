# 운영 가이드

[English](operations.md) | [한국어](operations.ko.md) · [Helper README](../README.ko.md)

지원되는 배포에서는 helper를 폐기 가능한 Docker workload로 실행합니다. 이미지는 HTTP
binary, full JDK 21, Node.js `24.15.0`, npm, immutable template bundle 하나를
제공합니다. WIZ Spring generator JAR는 별도로 공급합니다.

## Runtime 구조

```text
client
  │
  ▼
HTTP 검증 ── 동시성 gate ── wiz-spring 1.1.0 JAR
                                      │
                                      ▼
                               base 프로젝트 생성
                                      │
                                      ▼
                              remove + image overlay
                                      │
                                      ▼
                               제한된 ZIP 응답
```

Generator JAR는 의도적으로 helper 이미지에 넣지 않으며 helper source와 asset은
배포되는 WIZ Spring JAR에 넣지 않습니다. Compose는
`target/wiz-spring-1.1.0.jar`를 읽기 전용으로
`/opt/wiz-source/wiz-spring.jar`에 mount합니다. 시작할 때 entrypoint가 container
tmpfs로 복사한 뒤 UID/GID `10001`로 서비스를 실행합니다.

Generator JAR를 바꿀 때는 helper 이미지를 다시 빌드할 필요 없이 container만 다시
생성하면 됩니다. Registry 또는 overlay는 build time에 고정되므로 변경한 뒤 새 이미지를
빌드해야 합니다.

## 빌드 및 실행

저장소 최상위에서 실행합니다.

```bash
./mvnw clean package
docker compose -f helper/docker-compose.yaml up -d --build --wait
```

서비스 상태와 log를 확인합니다.

```bash
curl --fail-with-body http://127.0.0.1:8080/readyz
docker compose -f helper/docker-compose.yaml logs -f helper
```

Container를 종료하고 제거합니다.

```bash
docker compose -f helper/docker-compose.yaml down
```

기본 이미지만 빌드하려면 다음을 실행합니다.

```bash
docker build \
  -f helper/Dockerfile \
  -t wiz-spring-helper:1.1.0 \
  helper
```

Custom 이미지는 [template registry 문서](template-registry.ko.md)를 참고하세요.

## Image 및 Compose 설정

| 설정 | 기본값 | 용도 |
| --- | --- | --- |
| Build arg `WIZ_HELPER_TEMPLATE_FILE` | `registry.json` | 이미지에 stage할 `helper/templates` 아래 registry 파일명 |
| `WIZ_HELPER_PORT` | `8080` | Compose가 loopback에 공개할 host port |
| `WIZ_HELPER_MAX_CONCURRENT` | `2` | 동시 프로젝트 생성 수, 범위 1–8 |
| `WIZ_HELPER_GENERATION_TIMEOUT` | `45s` | 생성별 제한 시간, 범위 1s–5m |
| `WIZ_SPRING_SHA256` | 비어 있음 | Mount한 JAR의 선택적 예상 SHA-256 |

[`.env.example`](../.env.example)을 `helper/.env`로 복사하고 수정한 뒤 명시적으로
전달합니다.

```bash
docker compose \
  --env-file helper/.env \
  -f helper/docker-compose.yaml \
  up -d --build --wait
```

Compose 파일은 기본적으로 `127.0.0.1`에만 bind합니다. Reverse proxy나 ingress 앞에
배치할 때 deployment networking을 명시적으로 변경하세요.

## Helper process 설정

Go 서비스를 직접 개발할 때도 다음 변수를 사용할 수 있습니다. 표시한 값은 process
기본값이며 Dockerfile 또는 Compose가 일부 값을 override합니다.

| 변수 | Process 기본값 | 설명 |
| --- | --- | --- |
| `WIZ_HELPER_ADDR` | `127.0.0.1:8080` | Listen address, image/Compose는 `0.0.0.0:8080` 사용 |
| `WIZ_SPRING_JAR` | `../target/wiz-spring-1.1.0.jar` | Go process가 사용하는 generator 경로 |
| `WIZ_SPRING_SOURCE_JAR` | `/opt/wiz-source/wiz-spring.jar` | Container entrypoint의 읽기 전용 source mount |
| `WIZ_SPRING_SHA256` | 비어 있음 | 선택적인 64자 hexadecimal checksum |
| `WIZ_HELPER_JAVA_BIN` | `java` | Java executable |
| `WIZ_HELPER_WORK_DIR` | OS 임시 directory | 격리된 요청 workspace의 상위 경로, Compose는 `/work` 사용 |
| `WIZ_HELPER_MAX_CONCURRENT` | `2` | 범위 1–8 |
| `WIZ_HELPER_GENERATION_TIMEOUT` | `45s` | 범위 1s–5m |
| `WIZ_HELPER_ACQUIRE_TIMEOUT` | `2s` | 생성 slot 대기, 범위 100ms–30s |
| `WIZ_HELPER_TEMPLATE_REGISTRY` | `templates/registry.json` | 로컬 개발 registry, image entrypoint는 stage된 registry로 고정 |

Compose가 현재 전달하지 않는 process 설정은 service의 `environment` section을
확장하거나 같은 역할의 deployment 설정으로 공급하세요.

## 시작 및 상태 확인

Helper는 listener를 열기 전에 다음을 수행합니다.

1. Stage된 registry와 overlay를 검증합니다.
2. 선택적으로 JAR SHA-256을 확인합니다.
3. `java -jar ... --version` 결과가 정확히 `wiz-spring 1.1.0`인지 확인합니다.
4. 등록된 template마다 폐기 가능한 프로젝트를 생성, customize, 압축 및 정리합니다.

Template 또는 필요한 toolchain이 하나라도 잘못되면 시작에 실패합니다. `/healthz`는
process liveness를 반환하며 Docker healthcheck는 시작 probe를 통과한 뒤 `/readyz`를
사용합니다.

Generator artifact를 고정하려면 다음을 실행합니다.

```bash
sha256sum target/wiz-spring-1.1.0.jar
# 64자 값을 WIZ_SPRING_SHA256에 설정합니다.
```

같은 결과를 재현하려면 helper image digest, mount한 JAR digest, 요청 field가 모두
같아야 합니다.

## Resource 제한

서비스는 생성 전과 생성 중에 고정된 제한을 적용합니다.

| Resource | 제한 |
| --- | --- |
| Request body | 8 KiB |
| 저장하는 generator 출력 | 64 KiB |
| 동시 생성 | 기본 2, 최대 8 |
| Generator JVM heap | 최대 256 MiB |
| ZIP entry | 5,000개 |
| ZIP 압축 전 data | 128 MiB |
| ZIP 압축 후 data | 64 MiB |
| HTTP header | 16 KiB |

제공하는 Compose profile은 container를 CPU 2개, memory 1 GiB, PID 256개,
`/tmp` tmpfs 64 MiB, `/work` tmpfs 512 MiB로 제한합니다. 성공, 실패, timeout 또는
응답 완료 뒤 임시 workspace와 부분 archive를 정리합니다.

## Container 및 생성 보안

제공하는 deployment는 실행 경계를 좁게 유지합니다.

- 크기가 제한된 writable tmpfs와 read-only root filesystem을 사용합니다.
- 기본적으로 loopback에만 port를 공개합니다.
- `no-new-privileges`를 사용하고 모든 capability를 기본 제거한 뒤, mode가 `0600`일 수
  있는 mount JAR를 복사하고 UID/GID `10001`로 전환하는 entrypoint에
  `DAC_READ_SEARCH`, `SETUID`, `SETGID`만 추가합니다.
- 고정된 generator artifact와 template allowlist를 사용하고 shell interpolation 없이
  process argument로 내장 base를 전달합니다.
- 요청별 directory를 격리하며 사용자가 지정하는 filesystem 또는 Git URI import를
  제공하지 않습니다.
- Overlay와 생성된 ZIP에서 symlink와 일반 파일이 아닌 항목을 거부합니다.
- 완성되고 크기가 제한된 ZIP을 만든 뒤에만 `200` 응답을 시작합니다.
- Database, 영구 project cache 또는 upload 없이 stateless로 동작합니다.

각 deployment는 환경에 맞는 외부 접근 제어, network policy, TLS 종료, rate limit 및
감사 log 보존 방식을 직접 선택해야 합니다.

## 개발 및 검증

Helper 자체를 변경할 때는 host Go 설치가 필요 없는 container 검증을 사용합니다.

```bash
make -C helper test-container
make -C helper verify-jar-boundary
```

기본 Compose service가 실행 중일 때 다섯 가지 내장 template과 세 가지 요청 형식을
검증합니다.

```bash
make -C helper e2e
```

Example custom 이미지를 빌드하고 격리된 임시 container에서 검증합니다.

```bash
make -C helper e2e-custom
```

Host에서 직접 개발하려면 Go `1.24`, full JDK 21 이상, 지원되는 Node.js/npm,
`target/wiz-spring-1.1.0.jar`가 필요합니다.

```bash
make -C helper test
make -C helper run
```

`verify-jar-boundary`는 helper source, registry code, helper asset이 WIZ Spring JAR에
들어가지 않았는지 확인합니다.
