# Overflow Clipping Dropdown

증상: dropdown, menu, modal이 부모 영역에 잘린다.

해결:

- scroll container와 overlay container를 분리한다.
- 반복 item 내부에 overlay를 직접 넣는 구조를 피한다.
- z-index만 높이지 말고 `overflow`와 stacking context를 함께 확인한다.
