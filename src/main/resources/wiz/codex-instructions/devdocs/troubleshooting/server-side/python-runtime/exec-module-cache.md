# Class Cache And Bundle Reload

증상: 변경 전 class가 실행된다.

확인:

- build가 성공해 `bundle/app-api.jar`가 갱신됐는지 확인한다.
- `bundle/.wiz-build.json`의 dependency digest를 확인한다.
- 장기 resource는 runtime cache 폐기 시 닫히도록 구현한다.
- 서버 재시작 없이도 다음 dispatch에서 새 bundle을 읽는 것이 기본이나, 실패한 build는 반영되지 않는다.
