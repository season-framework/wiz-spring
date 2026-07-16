# Pug Template Reference

Angular template reference나 attribute 값이 빈 문자열일 때 Pug가 예상과 다르게 변환될 수 있다.

해결:

```pug
input(#searchInput type="text")
button(type="button" [disabled]="loading")
```

빈 값 attribute보다 명시적인 Angular binding을 사용한다.
