# Service Render Missing

증상: service/helper import 후 화면이 렌더되지 않는다.

확인:

- `src/angular/package.json`에 dependency가 있는지 확인한다.
- Angular build 로그에서 TypeScript compile error를 확인한다.
- browser console에서 runtime import error를 확인한다.
- generated bundle을 직접 수정하지 말고 source import를 수정한다.
