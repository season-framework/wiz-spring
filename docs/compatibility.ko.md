[English](compatibility.md) | [한국어](compatibility.ko.md)

# WIZ Spring 1.0 호환성

WIZ Spring 1.0은 새로운 프로젝트 모델입니다. 0.2.8을 포함한 0.2.x의 인플레이스
업그레이드가 아닙니다.

## 의도적으로 이전하지 않는 항목

1.0 생성기는 다음 0.2.x 개념을 탐지·변환·보존하지 않습니다.

- WIZ 백엔드 소스 변환 및 런타임 dispatcher
- 이전 런타임 설정과 생성된 빌드 산출물
- 기존 번들 구조 또는 systemd unit
- 1.0 템플릿 구조와 일치하지 않는 프론트엔드
- 프로젝트 내부 MCP mutation workflow

`create --path`, `create --uri`, `service` 명령은 0.2.x workspace나 bundle을 위한
마이그레이션 명령이 아닙니다.

## 지원하는 전환 방식

다음 중 하나를 선택하십시오.

1. 기존 애플리케이션에는 일치하는 0.2.x 런타임과 도구를 계속 사용합니다.
2. 새 1.0 프로젝트를 만든 뒤 표준 Spring 및 선택한 프론트엔드 구조로 코드를
   옮깁니다.
3. 기존 저장소가 이미 1.0 계약을 따르는 경우에만 import합니다.

Import할 Java source가 있다면 `src/main/java`에 있고 요청한 Java package를 사용해야
합니다. 또한 선택한 템플릿의 프론트엔드 source root와 일치해야 합니다. Target을
게시하기 전에 검증하며, 호환되지 않는 source tree를 추측·이동·부분 변환하지 않습니다.

허용하는 구조는 [프로젝트 생성](project-generation.ko.md#기존-소스-import)을,
전체 릴리스 경계는 [1.0.0 릴리스 노트](../release-log/1.0.0.md)를 참고하십시오.
