# WIZ Spring Project Helper

`wiz-spring create`를 HTTP로 제공하는 WIZ Spring `1.0.0` 전용 프로젝트 생성
helper입니다. 프로젝트 이름, Java package, frontend template을 보내면 완성된 프로젝트를
ZIP으로 반환합니다.

helper는 Docker 이미지로 빌드해 사용하는 별도 컴포넌트입니다. 소스와 template
registry는 Maven 소스 디렉터리인 `src/main` 밖의 `helper/`에 있으므로
`wiz-spring-1.0.0.jar`에 포함되지 않습니다. helper 이미지에는 선택한 registry와
그 registry가 참조하는 overlay만 들어가며, 선택되지 않은 registry/overlay와 generator
JAR는 들어가지 않습니다. JAR는 실행할 때 읽기 전용으로 mount합니다.

이 helper는 `0.2.8`을 포함한 `0.2.x` workspace를 변환하거나 migration하지 않습니다.
항상 WIZ Spring `1.0.0`의 신규 프로젝트 구조만 생성합니다.

```text
curl / HTTP client
      │
      ▼
입력 검증 ── 동시성 제한 ── 고정된 wiz-spring 1.0.0 JAR
                                  │
                                  ▼
                         built-in base 생성
                                  │
                                  ▼
                     remove + image 내 overlay 적용
                                  │
                                  ▼
                       실행 권한을 보존한 ZIP 응답
```

## 빠른 실행

WIZ Spring 저장소 최상위에서 generator JAR를 만든 다음 helper를 시작합니다.

```bash
./mvnw clean package
docker compose -f helper/docker-compose.yaml up -d --build --wait
```

`GET /`는 image registry의 기본 template과 template별 ID, base, 설명을 JSON으로
반환합니다. Compose는 다음 artifact를 container에 읽기 전용으로 mount합니다.

```text
target/wiz-spring-1.0.0.jar
```

helper 이미지는 full JDK 21, Node.js 24.15.0, npm, 정적 Go helper binary, 그리고
빌드할 때 선택한 template registry/overlay를 포함합니다. generator JAR는 이미지에
복사하지 않으므로 새 JAR를 적용할 때 helper 이미지를 다시 만들 필요가 없습니다.
다만 helper는 시작할 때 JAR의 `--version`이 정확히 `1.0.0`인지 검사하고, 폐기 가능한
각 registry template의 폐기 가능한 프로젝트를 생성·customize·압축·정리해 전체
toolchain과 모든 등록 template의 readiness를 확인합니다.

Maven이 root 권한으로 JAR를 만들어 mode가 `0600`이어도 동작하도록 entrypoint가
읽기 전용 mount를 container tmpfs로 복사한 다음 UID/GID `10001`로 권한을 낮춰
helper를 실행합니다. 이 복사 단계에만 `DAC_READ_SEARCH`, `SETUID`, `SETGID`
capability를 허용하며 UID/GID 전환 시 모두 제거합니다. 생성 요청 자체는 root
권한으로 실행되지 않습니다.

## curl로 프로젝트 생성

먼저 image에 등록된 template과 기본값을 확인합니다.

```bash
curl --fail-with-body http://127.0.0.1:8080/
```

`GET /api/v1/templates`도 같은 registry 정보를 반환합니다.

JSON 요청:

```bash
curl --fail-with-body \
  -X POST http://127.0.0.1:8080/api/v1/projects \
  -H 'Content-Type: application/json' \
  -d '{
    "projectName": "test-wiz",
    "packageName": "kr.nanoha.testwiz",
    "template": "angular-wiz"
  }' \
  -o test-wiz.zip
```

Form 요청도 지원합니다.

```bash
curl --fail-with-body \
  -X POST http://127.0.0.1:8080/api/v1/projects \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'projectName=test-react' \
  --data-urlencode 'packageName=kr.nanoha.testreact' \
  --data-urlencode 'template=react' \
  -o test-react.zip
```

Query string 요청도 지원합니다. Method는 `POST`를 사용합니다.

```bash
curl --fail-with-body \
  -X POST \
  'http://127.0.0.1:8080/api/v1/projects?projectName=test-html&packageName=kr.nanoha.testhtml&template=html' \
  -o test-html.zip
```

한 요청에서는 JSON body, form body, query string 중 한 방식만 사용해야 하며 서로
혼합할 수 없습니다.

`template`을 생략하면 registry의 `default`를 사용합니다. 기본 registry에는
`angular-wiz`, `angular`, `react`, `html`, `jsp`가 등록되어 있고 기본값은
`angular-wiz`입니다. 실제 목록은 이미지마다 달라질 수 있으므로
`GET /api/v1/templates`를 기준으로 사용하십시오.

프로젝트 이름에는 소문자 영문, 숫자, `.`, `_`, `-`만 사용할 수 있으며 시작과 끝은
영문 또는 숫자여야 합니다. 길이는 최대 64자입니다. 프로젝트 이름의 하이픈은
허용되지만 Java package segment의 하이픈은 Java 문법상 허용되지 않습니다. Java
package는 helper의 보수적인 HTTP 경계에 따라 ASCII Java identifier만 허용하고 전체
길이를 255자로 제한합니다. Unicode Java identifier가 필요한 경우에는 로컬
`wiz-spring create`를 직접 사용하십시오.

## 이미지에 template 등록하기

Template 구성은 런타임 API로 변경하지 않습니다. `helper/templates/registry.json`과
그 파일이 가리키는 overlay를 수정한 뒤 이미지를 다시 빌드합니다. 이렇게 하면 실행
중인 container에서 template이 바뀌지 않습니다. 재현 가능한 결과는 image digest,
mount한 generator JAR의 SHA-256, 요청 입력이 모두 같을 때 보장됩니다. 운영에서는
`WIZ_SPRING_SHA256`도 함께 고정하십시오.

Registry의 기본 형태는 다음과 같습니다.

```json
{
  "version": 1,
  "default": "angular-wiz",
  "templates": [
    {
      "id": "angular-wiz",
      "base": "angular-wiz",
      "description": "Angular WIZ frontend"
    },
    {
      "id": "company-react",
      "base": "react",
      "description": "React template with company defaults",
      "remove": ["README.md"],
      "overlay": "examples/company-react/overlay"
    }
  ]
}
```

각 항목은 다음 규칙을 따릅니다.

- `id`는 HTTP API에서 선택할 template 이름입니다. 새 ID를 사용하면 custom
  template이 등록됩니다.
- `base`는 generator JAR에 내장된 `angular-wiz`, `angular`, `react`, `html`, `jsp`
  중 하나여야 합니다. helper는 먼저 이 base를 생성합니다.
- `description`은 template 목록 JSON에 표시됩니다.
- `remove`는 생성된 프로젝트 root 기준의 선택적 상대 경로 목록입니다. overlay보다
  먼저 적용됩니다.
- `overlay`는 registry 파일이 있는 디렉터리를 기준으로 한 선택적 상대 디렉터리이며,
  그 내용을 생성된 프로젝트 root에 덮어씁니다.
- `default`는 `templates`에 실제로 존재하는 `id`여야 합니다.

기본 template을 제거하려면 registry의 `templates` 배열에서 해당 항목을 빼면 됩니다.
예를 들어 `jsp` 항목을 생략한 이미지에서는 `jsp`가 목록에 노출되지 않고 생성 요청도
거부됩니다. 기존 ID를 회사 표준 overlay로 바꿀 수도 있고, 위 예시처럼 새 ID를 추가할
수도 있습니다. `helper/templates/registry.example.json`은 기존 template 제거와
`company-react` 등록을 함께 보여주며 overlay 예시는
`helper/templates/examples/company-react/overlay`에 있습니다.

UTF-8 text overlay에서는 다음 placeholder를 사용할 수 있습니다.

| Placeholder | 치환 값 |
| --- | --- |
| `__WIZ_PROJECT_NAME__` | 요청한 프로젝트 이름 |
| `__WIZ_PACKAGE_ROOT__` | 요청한 Java package |
| `__WIZ_PACKAGE_PATH__` | package의 점을 `/`로 바꾼 경로 |
| `__WIZ_TEMPLATE_ID__` | registry에서 선택한 template `id` |
| `__WIZ_BASE_TEMPLATE__` | template의 built-in `base` |

Binary 파일은 그대로 복사되고 실행 권한은 보존됩니다. Registry의 `remove`와
`overlay`, 그리고 overlay 내부 경로에는 절대 경로, `..` path traversal, `.wiz`
segment를 사용할 수 없습니다. Symlink, device, socket도 허용되지 않습니다.
Custom template도 생성 후 `package.json`의 `wiz.frontend` 값은 선택한 `base`를
유지해야 합니다. 관련 source와 build script는 overlay에서 바꿀 수 있지만, 완전히
다른 build 종류가 필요하면 그 종류와 가장 가까운 built-in base를 선택하고 해당
base의 standalone build contract를 유지해야 합니다.

### 기본 이미지 빌드

저장소 최상위에서 기본 `helper/templates/registry.json`을 포함하는 이미지를 만듭니다.

```bash
docker build \
  -f helper/Dockerfile \
  -t wiz-spring-helper:1.0.0 \
  helper
```

### Custom 이미지 빌드

Registry와 overlay는 Docker build context인 `helper/templates` 아래에 두어야 합니다.
`WIZ_HELPER_TEMPLATE_FILE`에는 `helper/templates` 기준 registry 파일명을 지정합니다.

```bash
cp helper/templates/registry.example.json \
  helper/templates/registry.company.json

# registry.company.json과 그 overlay를 회사 정책에 맞게 수정
docker build \
  --build-arg WIZ_HELPER_TEMPLATE_FILE=registry.company.json \
  -f helper/Dockerfile \
  -t company/wiz-spring-helper:1.0.0 \
  helper
```

Compose를 사용하면 같은 build argument를 환경 변수로 전달할 수 있습니다.

```bash
WIZ_HELPER_TEMPLATE_FILE=registry.company.json \
  docker compose -f helper/docker-compose.yaml up -d --build --wait
```

`.env`로 관리하려면 다음처럼 helper 전용 환경 파일을 명시합니다.

```bash
cp helper/.env.example helper/.env
# helper/.env에 WIZ_HELPER_TEMPLATE_FILE=registry.company.json 설정
docker compose \
  --env-file helper/.env \
  -f helper/docker-compose.yaml \
  up -d --build --wait
```

Registry 또는 overlay를 바꾼 뒤에는 반드시 이미지를 다시 빌드해야 합니다.
최종 image layer에는 선택한 registry와 그 registry에서 참조한 overlay만 들어가므로,
같은 build context 아래의 미사용 회사 template 자산은 배포 image에 포함되지 않습니다.

## API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/` | 현재 image의 registry 기본값과 template 목록·설명 |
| `POST` | `/api/v1/projects` | 신규 프로젝트 ZIP 생성 |
| `GET` | `/api/v1/templates` | 현재 image의 template과 기본값 |
| `GET` | `/api/v1/version` | helper와 generator 버전 |
| `GET` | `/healthz` | liveness |
| `GET` | `/readyz` | startup version/create probe를 통과한 instance의 readiness |
| `GET` | `/openapi.yaml` | OpenAPI 3.1 문서 |

성공 응답은 `application/zip`이며 ZIP 내부에는 요청한 프로젝트 이름의 최상위
directory 하나만 들어 있습니다. 오류는 `application/problem+json`으로 반환하며
모든 응답에 `X-Request-ID`가 포함됩니다.

`--uri`와 `--path` import는 의도적으로 제공하지 않습니다. HTTP 요청에서 server의
filesystem이나 외부 Git URI를 읽는 경로를 열지 않고 신규 template 생성만 담당합니다.

## 설정

Docker image build 설정:

| Build argument | 기본값 | 설명 |
| --- | --- | --- |
| `WIZ_HELPER_TEMPLATE_FILE` | `registry.json` | `helper/templates`에서 image에 적용할 registry 파일 |

Container 실행 설정:

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `WIZ_HELPER_ADDR` | `127.0.0.1:8080` | listen address; Compose는 `0.0.0.0:8080` 사용 |
| `WIZ_SPRING_JAR` | `../target/wiz-spring-1.0.0.jar` | 로컬 실행용 고정 generator JAR |
| `WIZ_SPRING_SOURCE_JAR` | `/opt/wiz-source/wiz-spring.jar` | container에 read-only mount한 generator JAR |
| `WIZ_SPRING_SHA256` | 비어 있음 | 설정하면 startup에서 JAR SHA-256 일치 여부 검증 |
| `WIZ_HELPER_JAVA_BIN` | `java` | Java executable |
| `WIZ_HELPER_WORK_DIR` | OS temp directory | 요청별 임시 workspace parent |
| `WIZ_HELPER_MAX_CONCURRENT` | `2` | 동시 생성 수, 최대 8 |
| `WIZ_HELPER_GENERATION_TIMEOUT` | `45s` | create 제한 시간 |
| `WIZ_HELPER_ACQUIRE_TIMEOUT` | `2s` | 빈 generation slot 대기 시간 |
| `WIZ_HELPER_TEMPLATE_REGISTRY` | `templates/registry.json` | 로컬 실행 시 사용할 registry 경로; Docker image에서는 build argument로 고정 |

요청 body는 8 KiB, generator 출력은 64 KiB, ZIP entry는 5,000개로 제한합니다.
압축 전 128 MiB 또는 압축 후 64 MiB를 넘으면 요청을 실패시키며 임시 directory와
부분 ZIP은 제거합니다.

고정 artifact 검증을 사용하려면 JAR checksum을 helper 환경 파일에 넣습니다.

```bash
sha256sum target/wiz-spring-1.0.0.jar
# 출력된 64자리 값을 helper/.env의 WIZ_SPRING_SHA256에 설정
```

## 개발자 검증

배포 방식은 Docker image를 전제로 합니다. helper 구현 자체를 수정할 때는 저장소
최상위에서 다음 검증을 실행할 수 있습니다.

```bash
make -C helper test-container
make -C helper verify-jar-boundary
make -C helper e2e
make -C helper e2e-custom
```

`e2e-custom`은 example registry를 build argument로 선택한 별도 image를 만들고, 최종
image에 선택된 registry/overlay만 있는지 확인한 뒤 임시 container에서 삭제된 `jsp`와
추가된 `company-react` 계약을 검증합니다.

호스트에서 직접 실행하려면 Go 1.24, full JDK 21 이상, 지원되는 Node.js/npm, 그리고
`target/wiz-spring-1.0.0.jar`가 필요합니다.

```bash
make -C helper test
make -C helper run
```

## 구현 및 보안 원칙

- user input을 shell command로 조합하지 않고 process argument로 전달
- registry에서 검증된 template ID만 허용하고 CLI에는 built-in base만 전달
- 요청마다 격리된 임시 directory 사용 및 응답 종료 후 삭제
- ZIP을 완전히 만든 뒤 HTTP 200 응답 시작
- symlink, device, socket을 registry, overlay, ZIP에 포함하지 않음
- `mvnw`와 script의 Unix executable mode 보존
- 기본 동시 생성 2개와 request timeout 적용
- database, project cache, 사용자 업로드를 사용하지 않는 stateless 구조
- container root filesystem read-only, writable workspace는 크기가 제한된 tmpfs 사용

## 라이선스

MIT License로 배포합니다.
