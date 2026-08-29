# WIZ Spring Project Helper

[English](README.md) | [한국어](README.ko.md)

WIZ Spring `1.1.0` 프로젝트 템플릿을 다운로드 가능한 ZIP으로 만들어 주는
작고 단순한 Docker 전용 HTTP 서비스입니다. 사내 도구, CI, `curl` 기반 프로젝트
생성에 사용할 수 있습니다.

> [!IMPORTANT]
> 이 helper는 새로운 WIZ Spring `1.1.0` 프로젝트만 생성합니다. `0.2.8`을 포함한
> `0.2.x` workspace와 호환되지 않으며 이를 migration하지도 않습니다.

## 빠른 시작

### 요구 사항

- Docker 및 Compose
- WIZ Spring 저장소
- 로컬에서 빌드한 `target/wiz-spring-1.1.0.jar`

지원되는 배포 방식에서는 helper를 Docker workload로 실행합니다. Generator JAR는
이미지 밖에 유지하고 실행 시 읽기 전용으로 mount합니다.

### 1. Generator 빌드

저장소 최상위에서 실행합니다.

```bash
./mvnw clean package
```

### 2. Helper 시작

```bash
docker compose -f helper/docker-compose.yaml up -d --build --wait
```

기본 Compose 설정은 서비스를 `http://127.0.0.1:8080`에 공개합니다.

### 3. 템플릿 확인

```bash
curl --fail-with-body http://127.0.0.1:8080/
```

Root endpoint는 실행 중인 이미지에 등록된 기본값과 템플릿 전체를 반환합니다.

```json
{
  "default": "angular-wiz",
  "templates": [
    {
      "id": "angular-wiz",
      "base": "angular-wiz",
      "description": "Angular with WIZ source layout"
    }
  ]
}
```

### 4. 프로젝트 생성

`POST` 요청을 보내고 응답을 ZIP으로 저장합니다.

```bash
curl --fail-with-body \
  -X POST \
  'http://127.0.0.1:8080/api/v1/projects?projectName=demo-app&packageName=com.example.demo&template=angular-wiz' \
  -o demo-app.zip
```

`projectName`과 `packageName`은 필수입니다. `template`을 생략하면 registry의
기본값을 사용합니다. JSON과 form 요청도 지원하며, 요청 하나에서는 입력 방식 하나만
사용해야 합니다.

압축 파일에는 프로젝트 이름과 같은 최상위 directory 하나만 들어 있습니다. 생성된
프로젝트는 독립적으로 동작하므로 빌드하거나 실행할 때 helper가 필요하지 않습니다.

### 5. Helper 종료

```bash
docker compose -f helper/docker-compose.yaml down
```

## 문서

| 문서 | 내용 |
| --- | --- |
| [API 참조](docs/api.ko.md) | Endpoint, 요청 형식, 검증, 응답 및 오류 |
| [Template registry](docs/template-registry.ko.md) | Custom ID, base template, overlay, placeholder 및 이미지 빌드 |
| [운영 가이드](docs/operations.ko.md) | 설정, 제한, container 보안, checksum 및 개발자 검증 |
| [OpenAPI 3.1](internal/httpapi/openapi.yaml) | 기계가 읽을 수 있는 HTTP 계약 |

영문 문서도 함께 제공합니다.
[API](docs/api.md), [template registry](docs/template-registry.md),
[operations](docs/operations.md)를 참고하세요.

## 라이선스

[MIT](LICENSE)
