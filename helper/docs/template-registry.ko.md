# Template registry

[English](template-registry.md) | [한국어](template-registry.ko.md) · [Helper README](../README.ko.md)

Helper가 공개하는 template catalog는 Docker 이미지를 빌드할 때 고정됩니다. Runtime
registry 변경 기능은 없습니다. Registry 또는 overlay를 수정한 뒤 새 이미지를
빌드하세요. 그러면 template 동작을 특정 image digest와 연결할 수 있습니다.

## Customization 동작 방식

각 요청에서 helper는 다음 순서로 동작합니다.

1. 이미지 registry에서 공개 template `id`를 찾습니다.
2. 해당 항목의 내장 `base`로 `wiz-spring create`를 실행합니다.
3. 설정된 경로를 제거합니다.
4. 이미지에 포함된 overlay와 literal placeholder를 적용합니다.
5. 생성된 프로젝트의 build contract를 검증합니다.
6. 실행 권한을 보존한 ZIP을 반환합니다.

지원하는 base는 `angular-wiz`, `angular`, `react`, `html`, `jsp` 다섯 가지입니다.
Custom template은 새로운 generator 구현을 추가하는 대신 이 중 하나를 조합합니다.

## Registry 형식

기본 registry는 [`templates/registry.json`](../templates/registry.json)입니다. 이미지에는
`helper/templates` 바로 아래의 registry 파일을 선택할 수 있습니다.

```json
{
  "version": 1,
  "default": "company-react",
  "templates": [
    {
      "id": "react",
      "base": "react",
      "description": "React"
    },
    {
      "id": "company-react",
      "base": "react",
      "description": "Company React starter",
      "remove": ["README.md"],
      "overlay": "examples/company-react/overlay"
    }
  ]
}
```

| Field | 필수 | 의미 |
| --- | --- | --- |
| `version` | 예 | Registry schema 버전, 현재 `1` |
| `default` | 예 | 요청에서 `template`을 생략했을 때 선택할 기존 template `id` |
| `templates` | 예 | API에 순서대로 공개할 allowlist, 1–64개 항목 |
| `templates[].id` | 예 | 공개할 1–64자의 ASCII 소문자 slug |
| `templates[].base` | 예 | 내장 generator template 다섯 가지 중 하나 |
| `templates[].description` | 예 | 비어 있지 않은 API 설명, 최대 256 byte |
| `templates[].remove` | 아니요 | Overlay 적용 전에 생성된 base에서 제거할 경로 |
| `templates[].overlay` | 아니요 | Registry 파일 기준 overlay directory |

ID는 중복될 수 없습니다. `react` 같은 내장 ID는 같은 이름의 `react` base를 유지해야
합니다. `company-react` 같은 새 ID에는 지원되는 base 중 하나를 사용할 수 있습니다.

Template을 삭제하려면 해당 항목을 생략하세요. 목록에서 사라지며 그 ID를 요청하면
`422`를 반환합니다. 기존 template 항목에 `remove`와 `overlay`를 추가해 기본값을
교체하거나 별도의 custom ID를 추가할 수 있습니다.

## Overlay

모든 `remove` 경로를 지운 뒤 생성된 프로젝트에 overlay를 복사합니다. 기존 일반
파일은 교체하고 필요한 directory는 생성하며 Unix 실행 권한은 보존합니다. 설정된
remove 경로는 선택한 base에 실제로 있어야 하므로 registry drift가 조용히 지나가지
않습니다.

Remove 경로와 UTF-8 overlay 경로 및 파일 내용에서는 정해진 literal placeholder를
사용할 수 있습니다.

| Placeholder | 치환 값 |
| --- | --- |
| `__WIZ_PROJECT_NAME__` | 요청한 프로젝트 이름 |
| `__WIZ_PACKAGE_ROOT__` | 요청한 Java package |
| `__WIZ_PACKAGE_PATH__` | Package의 점을 `/`로 바꾼 경로 |
| `__WIZ_TEMPLATE_ID__` | 선택한 공개 template ID |
| `__WIZ_BASE_TEMPLATE__` | 선택한 내장 base |

Binary 파일은 치환하지 않고 복사합니다. 알 수 없는 `__WIZ_*__` placeholder는 해석되지
않은 채 남는 대신 registry 검증을 실패시킵니다.

저장소에는 동작하는 예제가 있습니다.

- [`templates/registry.example.json`](../templates/registry.example.json)은 `jsp`를
  제거하고 `company-react`를 추가합니다.
- [`templates/examples/company-react/overlay`](../templates/examples/company-react/overlay)는
  overlay 내용을 제공합니다.

## Custom 이미지 빌드

Registry 파일과 overlay는 Docker build context인 `helper/templates` 아래에 있어야
합니다. 저장소 최상위에서 실행합니다.

```bash
cp helper/templates/registry.example.json \
  helper/templates/registry.company.json

docker build \
  --build-arg WIZ_HELPER_TEMPLATE_FILE=registry.company.json \
  -f helper/Dockerfile \
  -t company/wiz-spring-helper:1.1.0 \
  helper
```

Compose에도 같은 선택을 전달할 수 있습니다.

```bash
WIZ_HELPER_TEMPLATE_FILE=registry.company.json \
  docker compose -f helper/docker-compose.yaml up -d --build --wait
```

로컬 설정을 유지하려면 다음처럼 환경 파일을 명시합니다.

```bash
cp helper/.env.example helper/.env
# helper/.env에서 WIZ_HELPER_TEMPLATE_FILE=registry.company.json 설정
docker compose \
  --env-file helper/.env \
  -f helper/docker-compose.yaml \
  up -d --build --wait
```

이미지 staging 단계는 선택한 registry를 검증하고 그 registry와 참조하는 overlay만
복사합니다. Build context에 있는 다른 registry와 사용하지 않는 회사 template 자산은
최종 이미지에 들어가지 않습니다.

## 안전 규칙과 제한

Registry, remove 및 overlay 경로는 신뢰할 수 없는 build input으로 취급합니다.

- 경로는 정규화된 `/` 구분 상대 경로여야 합니다.
- 절대 경로, `..`, glob 문법, 제어 문자, `.wiz` component를 거부합니다.
- 상대 경로 하나는 최대 512 byte입니다.
- 중복되거나 겹치는 remove 경로와 치환 후 충돌하는 overlay 경로를 거부합니다.
- Symlink, device, socket 및 기타 특수 파일을 거부합니다.
- Overlay는 최대 2,000개 항목, 파일당 8 MiB, 전체 32 MiB입니다.
- Registry 파일은 최대 1 MiB입니다.

Customization 뒤 `package.json`은 유효해야 하고 `wiz.frontend` 값이 선택한 base와
같아야 하며 `@season-framework/wiz-frontend`에 의존하면 안 됩니다. `package-lock.json`이
있으면 같은 금지 dependency를 검사합니다. 이를 통해 각 base template의 독립 build
contract를 유지합니다.

## 변경 사항 검증

Custom E2E suite는 example 이미지를 빌드하고 선택한 registry bundle만 stage되었는지
확인한 뒤 삭제한 template과 추가한 template의 계약을 모두 검증합니다.

```bash
make -C helper e2e-custom
```

전체 검증 명령은 [운영 가이드](operations.ko.md)를 참고하세요.
