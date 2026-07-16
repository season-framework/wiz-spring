# Tool Guide

WIZ Spring 작업에서 외부 도구는 현재 source와 테스트를 확인하기 위한 보조 수단이다.

## 우선순위

1. 현재 repo의 `wiz-spring/README.md`, `src/main/java`, sample template을 확인한다.
2. CLI help와 테스트 스크립트로 실제 동작을 확인한다.
3. Spring Boot나 Maven 동작 확인이 필요할 때는 공식 문서를 참조한다.

## 사용 원칙

- 불확실한 runtime API는 source에서 확인한다.
- 대량 파일 검색은 `rg`를 사용한다.
- 문서 생성 시 현재 구현에 없는 기능을 완성된 기능처럼 쓰지 않는다.
- 요청자가 특정 외부 도구를 요구하지 않았다면 repo 내 source와 테스트를 우선한다.
