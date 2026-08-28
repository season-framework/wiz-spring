[English](README.md) | [한국어](README.ko.md)

# WIZ Spring 문서

루트 [README](../README.ko.md)은 프로젝트를 실행하기 위한 가장 짧은 경로만
설명합니다. 생성 규칙, 배포 모델, 이전 버전과의 경계가 필요할 때 아래 문서를
참고하십시오.

| 문서 | 내용 |
| --- | --- |
| [프로젝트 생성](project-generation.ko.md) | 요구 사항, CLI, 템플릿, import, 샘플 애플리케이션, 프론트엔드 식별 |
| [빌드와 배포](build-and-deployment.ko.md) | 프로젝트 스크립트, API prefix, 번들, Docker Compose, systemd 서비스 |
| [1.0 호환성](compatibility.ko.md) | 0.2.x와의 단절 및 지원하는 이전 방식 |
| [AI 인스트럭션](ai-instructions.ko.md) | 인스트럭션 원본과 생성 프로젝트의 배치 위치 |
| [HTTP 프로젝트 Helper](../helper/README.ko.md) | Docker 기반 프로젝트 생성과 커스텀 템플릿 registry |
| [릴리스 노트](../release-log/README.md) | 버전 이력과 1.0.0 릴리스 경계 |

전체 CLI 옵션은 설치된 명령에서 확인할 수 있습니다.

```bash
wiz-spring --help
wiz-spring create --help
wiz-spring service --help
```
