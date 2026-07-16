# Angular Host Element Styling

증상: component root 스타일이 적용되지 않는다.

확인:

- WIZ App selector는 `app.json.template`과 Angular component selector 생성 규칙을 따른다.
- root layout을 깨지 않도록 host element에는 display와 size를 명시한다.
- 전역 theme은 `src/angular/styles`, app-local style은 `view.scss`에 둔다.
