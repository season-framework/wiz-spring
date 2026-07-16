# Portal wiz.call Routing

증상: portal app에서 `wiz.call()`이 404를 반환한다.

확인:

- frontend namespace가 `portal.{package}.{app}` 형태인지 확인한다.
- backend file이 `src/portal/{package}/app/{app}/api.java`에 있는지 확인한다.
- `app.json.api.handler`를 명시했다면 class 이름과 package가 build 결과와 일치해야 한다.
- `/wiz/api/{namespace}/{function}` URL을 curl로 직접 호출해 본다.
