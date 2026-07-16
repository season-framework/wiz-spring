# Styles Import Missing

증상: 전역 스타일 또는 package 스타일이 누락된다.

확인:

- Angular 전역 스타일은 `src/angular/styles/styles.scss` 또는 angular build options에 포함한다.
- app-local 스타일은 해당 app의 `view.scss`에 둔다.
- portal package style은 `portal.json.use_styles`와 build 포함 여부를 확인한다.
