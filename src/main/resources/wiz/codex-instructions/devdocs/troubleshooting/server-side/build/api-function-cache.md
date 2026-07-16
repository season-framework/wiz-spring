# API Build And Reload

증상: `api.java` 수정이 서버에 반영되지 않는다.

확인:

- `project build --clean`이 성공했는지 확인한다.
- `bundle/.wiz-build.json` mtime과 phase를 확인한다.
- compile error가 있으면 이전 bundle이 계속 사용될 수 있다.
- app/route/socket/model dispatch는 bundle 기준 class를 읽으므로 source 수정만으로는 반영되지 않는다.
