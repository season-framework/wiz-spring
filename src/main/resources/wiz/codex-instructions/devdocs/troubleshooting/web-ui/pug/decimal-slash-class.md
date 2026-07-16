# Pug Decimal And Slash Class

Pug class shorthand에서 `/`, `:` 같은 문자가 들어간 utility class는 그대로 쓰면 파싱 오류가 날 수 있다.

해결:

```pug
div(class="w-1/2 md:w-1/3")
```

복잡한 class는 attribute string으로 작성한다.
