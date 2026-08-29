# API 참조

[English](api.md) | [한국어](api.ko.md) · [Helper README](../README.ko.md)

Helper는 신규 프로젝트 생성을 위한 의도적으로 작은 HTTP API만 제공합니다. CLI의
filesystem 또는 remote URI import 기능은 노출하지 않습니다.

## Endpoint

| Method | 경로 | 응답 |
| --- | --- | --- |
| `GET` | `/` | Registry 기본값과 사용 가능한 템플릿 |
| `POST` | `/api/v1/projects` | 생성된 프로젝트 ZIP |
| `GET` | `/api/v1/templates` | `/`와 같은 템플릿 정보 |
| `GET` | `/api/v1/version` | Helper 및 generator 버전 |
| `GET` | `/healthz` | Liveness 상태 |
| `GET` | `/readyz` | Readiness 상태 |
| `GET` | `/openapi.yaml` | 내장 OpenAPI 3.1 문서 |

모든 응답에는 `X-Request-ID`가 포함됩니다. 실패한 요청을 server log에서 조사할 때
이 값을 보관하세요.

## 템플릿 목록

```bash
curl --fail-with-body http://127.0.0.1:8080/
```

```json
{
  "default": "angular-wiz",
  "templates": [
    {
      "id": "angular-wiz",
      "base": "angular-wiz",
      "description": "Angular with WIZ source layout"
    },
    {
      "id": "react",
      "base": "react",
      "description": "React"
    }
  ]
}
```

Template ID는 이미지의 registry에서 정의합니다. 기본 registry가 설치되어 있다고
가정하지 말고 항상 실행 중인 instance에서 목록을 확인하세요.

## 프로젝트 생성

`POST /api/v1/projects`는 아래 입력 방식 중 정확히 하나만 받습니다. Query string과
request body를 함께 보내면 안 됩니다.

### Query string

```bash
curl --fail-with-body \
  -X POST \
  'http://127.0.0.1:8080/api/v1/projects?projectName=demo-app&packageName=com.example.demo&template=react' \
  -o demo-app.zip
```

Query field는 하나씩만 나타나야 하며 알 수 없는 field는 거부됩니다.

### JSON

```bash
curl --fail-with-body \
  -X POST http://127.0.0.1:8080/api/v1/projects \
  -H 'Content-Type: application/json' \
  -d '{
    "projectName": "demo-app",
    "packageName": "com.example.demo",
    "template": "react"
  }' \
  -o demo-app.zip
```

Body에는 JSON object 하나만 있어야 합니다. 알 수 없는 property와 뒤따르는 JSON
값은 거부됩니다.

### Form

```bash
curl --fail-with-body \
  -X POST http://127.0.0.1:8080/api/v1/projects \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'projectName=demo-app' \
  --data-urlencode 'packageName=com.example.demo' \
  --data-urlencode 'template=react' \
  -o demo-app.zip
```

Form field는 하나씩만 나타나야 하며 알 수 없는 field는 거부됩니다.

## 요청 field

| Field | 필수 | 규칙 |
| --- | --- | --- |
| `projectName` | 예 | 1–64자의 ASCII 소문자; 영문 또는 숫자로 시작하고 끝남; 중간에 `.`, `_`, `-` 허용 |
| `packageName` | 예 | 1–255자; 점으로 구분한 ASCII Java 25 identifier; Java keyword와 `java` namespace는 거부 |
| `template` | 아니요 | 이미지에 등록된 1–64자의 ASCII 소문자 slug; 생략하면 registry 기본값 사용 |

프로젝트 이름에는 하이픈을 사용할 수 있습니다. 하이픈은 Java identifier에 유효하지
않으므로 Java package segment에는 사용할 수 없습니다. HTTP 경계에서는 의도적으로
보수적인 ASCII identifier 부분집합만 허용합니다. Unicode Java identifier가 필요하면
로컬 CLI를 사용하세요.

`template`을 생략하면 registry 기본값을 선택합니다. 빈 값을 명시하거나 JSON
`null`을 보내면 유효하지 않습니다.

## 성공 응답

생성에 성공하면 `Content-Type: application/zip`과 함께 `200 OK`를 반환합니다. ZIP에는
`projectName`과 같은 이름의 최상위 directory 하나만 있으며 `mvnw` 같은 파일의 실행
권한도 보존됩니다.

생성 응답에는 다음 metadata header가 포함됩니다.

| Header | 의미 |
| --- | --- |
| `Content-Disposition` | 권장 `<projectName>.zip` 파일 이름 |
| `X-Request-ID` | 요청 연관 ID |
| `X-Wiz-Spring-Version` | Generator 버전 |
| `X-Project-Template` | 공개 registry template ID |
| `X-Base-Template` | Customization 전에 사용한 내장 template |

## 오류 응답

오류는 `application/problem+json` 형식입니다.

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 422,
  "detail": "packageName: packageName segment ...",
  "code": "validation_failed",
  "requestId": "9ee5d9ef30ed674b5a7e2f4d"
}
```

| 상태 | 일반적인 원인 |
| --- | --- |
| `400` | 잘못되거나 혼합된 입력, 중복 또는 알 수 없는 field |
| `413` | 8 KiB를 초과한 request body |
| `415` | Body `Content-Type` 누락 또는 미지원 형식 |
| `422` | 잘못된 field 값 또는 등록되지 않은 template |
| `429` | 동시 생성 slot을 acquire timeout 안에 얻지 못함 |
| `500` | Generator, customization 또는 압축 실패 |
| `504` | 설정된 생성 시간 초과 |

알 수 없는 경로는 `404`, 지원하지 않는 method는 `Allow` header와 함께 `405`를
반환합니다. Generator command 출력은 크기를 제한하고 경로를 정리해 log에 남기며 HTTP
오류 응답에는 노출하지 않습니다.

## 상태와 버전

```bash
curl --fail-with-body http://127.0.0.1:8080/healthz
curl --fail-with-body http://127.0.0.1:8080/readyz
curl --fail-with-body http://127.0.0.1:8080/api/v1/version
```

프로세스는 JAR 버전 검사와 등록된 모든 template의 폐기 가능한 생성 probe가 통과한
뒤에만 요청을 받기 시작합니다. Compose healthcheck는 `/readyz`를 사용합니다.

## OpenAPI

표준 계약은 저장소의 [`internal/httpapi/openapi.yaml`](../internal/httpapi/openapi.yaml)에
있으며 실행 중인 instance의 `/openapi.yaml`에서도 확인할 수 있습니다.
