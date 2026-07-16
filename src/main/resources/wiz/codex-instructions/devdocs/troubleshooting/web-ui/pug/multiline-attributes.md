# Pug Multiline Attributes

증상: multiline attribute에서 template compile 오류가 발생한다.

해결:

- 긴 class는 한 문자열로 묶거나 component property로 분리한다.
- object/array literal은 `view.ts`에서 계산한다.
- Pug indentation을 엄격히 맞춘다.
