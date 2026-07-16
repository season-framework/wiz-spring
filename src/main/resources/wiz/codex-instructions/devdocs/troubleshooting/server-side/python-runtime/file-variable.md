# File Path Context

Spring project code에는 숨은 file 전역 변수에 의존하지 않는다.

대신 `wiz.project()`의 root를 기준으로 경로를 계산한다.

```java
Path config = wiz.project().configRoot().resolve("pwa/sw.js").normalize();
```

사용자 입력 경로는 safe path 정책으로 제한한다.
