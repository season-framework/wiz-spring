# Streaming Response Context

Spring에서 streaming 응답이 필요하면 request scope 객체를 장시간 보관하지 않는다.

원칙:

- stream 시작 전에 필요한 session/config/model 값을 immutable 값으로 복사한다.
- DB connection이나 transaction을 열린 채로 stream에 넘기지 않는다.
- timeout과 client disconnect 처리를 명시한다.
- 일반 App API는 짧은 요청/응답으로 유지한다.
